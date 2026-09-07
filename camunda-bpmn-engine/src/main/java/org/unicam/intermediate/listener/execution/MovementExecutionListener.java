package org.unicam.intermediate.listener.execution;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.model.bpmn.instance.DataInputAssociation;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.ItemAwareElement;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.Participant;
import org.unicam.intermediate.models.enums.TaskType;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.environmental.movement.MovementTaskRegistry;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.service.participant.UserParticipantMappingService;
import org.unicam.intermediate.service.xml.AbstractXmlService;
import org.unicam.intermediate.service.xml.XmlServiceDispatcher;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.unicam.intermediate.utils.Constants.*;

@Slf4j
@Component(movementExecutionListenerBeanName)
public class MovementExecutionListener implements ExecutionListener {

    private static final String TIMER_LOCAL_NAME = "timer";
    private static final String BPMN_ERROR_CODE_VAR = "__spaceBpmnErrorCode";
    private static final String BPMN_ERROR_MESSAGE_VAR = "__spaceBpmnErrorMessage";
    private static final String PARTICIPANT_DESTINATION_PREFIX = "Participant.";
    private static final String PARTICIPANT_POSITION_SUFFIX = ".position";

    private final XmlServiceDispatcher dispatcher;
    private final ParticipantService participantService;
    private final ParticipantDataService participantDataService;
    private final EnvironmentDataService environmentDataService;
    private final UserParticipantMappingService userParticipantMapping;
    private final MovementTaskRegistry movementTaskRegistry;

    public MovementExecutionListener(XmlServiceDispatcher dispatcher,
                                     ParticipantService participantService,
                                     ParticipantDataService participantDataService,
                                     EnvironmentDataService environmentDataService,
                                     UserParticipantMappingService userParticipantMapping,
                                     MovementTaskRegistry movementTaskRegistry) {
        this.dispatcher = dispatcher;
        this.participantService = participantService;
        this.participantDataService = participantDataService;
        this.environmentDataService = environmentDataService;
        this.userParticipantMapping = userParticipantMapping;
        this.movementTaskRegistry = movementTaskRegistry;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleMovementStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleMovementEnd(execution);
        }
    }

    private void handleMovementStart(DelegateExecution execution) {
        AbstractXmlService svc = dispatcher.get(SPACE_NS.getNamespaceUri(), TaskType.MOVEMENT);
        String raw = extractDestinationFromDataObject(execution);
        if (raw == null || raw.isBlank()) {
            raw = svc.extractRaw(execution);
        }

        String value = evaluateExpression(raw, execution);
        String destination = resolveDestinationValue(value, execution);

        String finalDestination = (destination != null && !destination.isBlank()) ? destination : value;
        Double timerValue = extractTimerValue(execution);

        Participant participant = participantService.resolveCurrentParticipant(execution);

        if (finalDestination != null) {
            boolean destinationIsPhysicalPlace = environmentDataService.getPhysicalPlace(finalDestination).isPresent();
            boolean destinationIsLogicalPlace = environmentDataService.resolveLogicalPlaceId(finalDestination).isPresent();

            if (participant != null && destinationIsLogicalPlace) {
                List<String> matchingPhysicalPlaces = movementTaskRegistry
                        .resolveMatchingPhysicalPlaceIdsForLogicalDestination(finalDestination);

                if (matchingPhysicalPlaces.isEmpty()) {
                    log.warn("[MOVEMENT] Logical destination '{}' has no matching physical places for participant '{}'",
                            finalDestination, participant.getId());
                }
            }

            if (participant != null && destinationIsPhysicalPlace) {
                String participantId = participant.getId();
                participantDataService.getParticipant(participantId).ifPresent(current -> {
                    String currentPosition = current.getPosition();
                    if (currentPosition != null && !currentPosition.isBlank()) {
                        boolean reachable = environmentDataService.existsPathBetweenPhysicalPlaces(currentPosition, finalDestination);
                        if (!reachable) {
                            log.warn("[MOVEMENT] No path found from '{}' to '{}' for participant '{}'",
                                    currentPosition, finalDestination, participantId);
                        }
                    }
                });
            }

            svc.patchInstanceValue(execution, finalDestination);
            var activityId = execution.getCurrentActivityId();
            String varKey = activityId + "." + svc.getLocalName();
            execution.setVariable(varKey, finalDestination);
        }

        String businessKey = execution.getBusinessKey();

        String userId = (String) execution.getVariable("userId");

        if (userId != null && participant != null && businessKey != null) {
            userParticipantMapping.registerUserAsParticipant(
                    businessKey,
                    userId,
                    participant.getId()
            );

            log.info("[MOVEMENT] Auto-registered user {} as participant {} for BK {}",
                    userId, participant.getId(), businessKey);
        }

        String activityName = execution.getCurrentActivityName();

        log.info("[MOVEMENT] WAITING | Activity: {} - {} | Participant: {} | Destination: {} | Timer: {}",
                execution.getCurrentActivityId(),
                activityName != null ? activityName : "(unnamed)",
                participant != null ? participant.getId() : "(none)",
                finalDestination != null ? finalDestination : "unknown location",
                timerValue != null ? timerValue : "(empty)");

        if (participant != null && finalDestination != null && !finalDestination.startsWith("${")) {
            String currentPosition = participantDataService.getParticipant(participant.getId())
                    .map(org.unicam.intermediate.models.pojo.Participant::getPosition)
                    .orElse(null);

            if (currentPosition != null && !currentPosition.isBlank()) {
                movementTaskRegistry.registerTask(
                        participant.getId(),
                        execution.getId(),
                        execution.getCurrentActivityId(),
                        finalDestination,
                        timerValue
                );
            } else {
                log.info("[MOVEMENT] Participant {} has no position set yet. Registration deferred to tour calculation.",
                        participant.getId());
            }
        }

        // Registra il task nel registry per il controllo periodico
       /* if (participant != null && destination != null) {
            movementTaskRegistry.registerTask(
                    participant.getId(),
                    execution.getId(),
                    execution.getCurrentActivityId(),
                    finalDestination,
                    timerValue
            );
        }*/
    }

    private void handleMovementEnd(DelegateExecution execution) {
        String bpmnErrorCode = (String) execution.getVariableLocal(BPMN_ERROR_CODE_VAR);
        String bpmnErrorMessage = (String) execution.getVariableLocal(BPMN_ERROR_MESSAGE_VAR);
        execution.removeVariableLocal(BPMN_ERROR_CODE_VAR);
        execution.removeVariableLocal(BPMN_ERROR_MESSAGE_VAR);

        AbstractXmlService svc = dispatcher.get(SPACE_NS.getNamespaceUri(), TaskType.MOVEMENT);
        String raw = svc.extractRaw(execution);
        svc.restoreInstanceValue(execution, raw);

        // Rimuovi il task dal registry quando termina
        Participant participant = participantService.resolveCurrentParticipant(execution);
        if (participant != null) {
            movementTaskRegistry.removeTask(participant.getId(), execution.getId());
            log.info("[MOVEMENT] Task completed for participant {} (execution={})",
                    participant.getId(), execution.getId());
        }

        if (bpmnErrorCode != null && !bpmnErrorCode.isBlank()) {
            throw new BpmnError(
                    bpmnErrorCode,
                    bpmnErrorMessage != null ? bpmnErrorMessage : "Movement task failed"
            );
        }
    }

    private String extractDestinationFromDataObject(DelegateExecution execution) {
        ModelElementInstance modelElement = execution.getBpmnModelElementInstance();
        if (!(modelElement instanceof Task task)) {
            return null;
        }

        Collection<DataInputAssociation> inputAssociations = task.getDataInputAssociations();
        if (inputAssociations != null && !inputAssociations.isEmpty()) {
            for (DataInputAssociation assoc : inputAssociations) {
                if (assoc.getSources() == null) continue;
                for (ItemAwareElement source : assoc.getSources()) {
                    String name = source.getAttributeValue("name");
                    if (name != null && name.trim().startsWith("${") && name.trim().endsWith("}")) {
                        return name.trim();
                    }
                }
            }
        }

        return null;
    }

    private String evaluateExpression(String raw, DelegateExecution execution) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String trimmed = raw.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
            return trimmed;
        }

        String exprStr = trimmed.substring(2, trimmed.length() - 1).trim();

        try {
            var processEngineConfig = Context.getProcessEngineConfiguration();
            if (processEngineConfig != null) {
                Object evaluated = processEngineConfig
                        .getExpressionManager()
                        .createExpression(trimmed)
                        .getValue(execution);

                if (evaluated != null) {
                    return String.valueOf(evaluated);
                }
            }
        } catch (Exception e) {
            log.debug("[MOVEMENT] Camunda native expression evaluation deferred for '{}': {}", trimmed, e.getMessage());
        }

        if (exprStr.contains(".")) {
            String[] parts = exprStr.split("\\.", 2);
            String rootVarName = parts[0];
            String path = parts[1];

            Object rootObj = execution.getVariable(rootVarName);
            if (rootObj != null) {
                Object resolvedObj = resolveNestedProperty(rootObj, path);
                if (resolvedObj != null) {
                    return String.valueOf(resolvedObj);
                }
            }
        } else {
            Object directVar = execution.getVariable(exprStr);
            if (directVar != null) {
                return String.valueOf(directVar);
            }
        }

        return trimmed;
    }

    private Object resolveNestedProperty(Object root, String path) {
        try {
            if (path.contains("[") && path.endsWith("]")) {
                String propName = path.substring(0, path.indexOf("["));
                int index = Integer.parseInt(path.substring(path.indexOf("[") + 1, path.length() - 1));

                Object listObj = root;
                if (!propName.isBlank()) {
                    Field field = root.getClass().getDeclaredField(propName);
                    field.setAccessible(true);
                    listObj = field.get(root);
                }

                if (listObj instanceof List<?> list && index < list.size()) {
                    return list.get(index);
                } else if (listObj != null && listObj.getClass().isArray()) {
                    return Array.get(listObj, index);
                }
            }
        } catch (Exception e) {
            log.warn("[MOVEMENT] Unable to parse path '{}' on object: {}", path, root.getClass().getSimpleName());
        }
        return null;
    }

    private Double extractTimerValue(DelegateExecution execution) {
        String raw = extractExtensionValue(execution, TIMER_LOCAL_NAME);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed < 0) {
                log.warn("[MOVEMENT] Invalid negative timer '{}' for activity {}. Ignoring.",
                        raw, execution.getCurrentActivityId());
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("[MOVEMENT] Invalid non-numeric timer '{}' for activity {}. Ignoring.",
                    raw, execution.getCurrentActivityId());
            return null;
        }
    }

    private String extractExtensionValue(DelegateExecution execution, String localName) {
        ModelElementInstance modelElement = execution.getBpmnModelElementInstance();
        if (!(modelElement instanceof Task task)) {
            return null;
        }

        ExtensionElements extensionElements = task.getExtensionElements();
        if (extensionElements == null) {
            return null;
        }

        return extensionElements.getDomElement().getChildElements().stream()
                .filter(domElement -> isSpaceElement(domElement, localName))
                .map(DomElement::getTextContent)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private boolean isSpaceElement(DomElement domElement, String localName) {
        return localName.equalsIgnoreCase(domElement.getLocalName())
                && SPACE_NS.getNamespaceUri().equals(domElement.getNamespaceURI());
    }

    private String resolveDestinationValue(String value, DelegateExecution execution) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.startsWith(PARTICIPANT_DESTINATION_PREFIX)
                && trimmed.length() > PARTICIPANT_DESTINATION_PREFIX.length()) {
            String participantRef = normalizeParticipantReference(
                trimmed.substring(PARTICIPANT_DESTINATION_PREFIX.length()).trim()
            );

            Optional<org.unicam.intermediate.models.pojo.Participant> participantOpt =
                    participantDataService.getParticipantByName(participantRef)
                            .or(() -> participantDataService.getParticipant(participantRef));

            if (participantOpt.isEmpty()) {
                throw new BpmnError(
                        "unreachableDestination",
                        String.format("Destination '%s' references unknown participant '%s'", trimmed, participantRef)
                );
            }

            String participantPosition = participantOpt.get().getPosition();
            if (participantPosition == null || participantPosition.isBlank()) {
                throw new BpmnError(
                        "unreachableDestination",
                        String.format("Destination '%s' references participant '%s' without a valid position", trimmed, participantRef)
                );
            }

            String resolved = environmentDataService.resolvePhysicalPlaceId(participantPosition)
                    .or(() -> environmentDataService.resolveLogicalPlaceId(participantPosition))
                    .orElse(participantPosition);

            log.info("[MOVEMENT] Resolved '{}' to participant position '{}'", trimmed, resolved);
            return resolved;
        }

        return environmentDataService.resolvePhysicalPlaceId(trimmed)
                .or(() -> environmentDataService.resolveLogicalPlaceId(trimmed))
                .orElse(trimmed);
    }

    private String normalizeParticipantReference(String participantRef) {
        if (participantRef == null) {
            return null;
        }

        String normalized = participantRef.trim();
        if (normalized.toLowerCase().endsWith(PARTICIPANT_POSITION_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - PARTICIPANT_POSITION_SUFFIX.length()).trim();
        }
        return normalized;
    }
}