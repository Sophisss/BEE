package org.unicam.intermediate.listener.execution;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.dto.TourResponse;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.service.ProcessVariableService;
import org.unicam.intermediate.service.environmental.EnvironmentalTaskRegistry;
import org.unicam.intermediate.service.environmental.ParticipantTourService;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;

import java.time.Instant;
import java.util.*;

import static org.unicam.intermediate.utils.Constants.SPACE_NS;
import static org.unicam.intermediate.utils.Constants.genericTaskExecutionListenerBeanName;

@Slf4j
@Component(genericTaskExecutionListenerBeanName)
public class GenericTaskExecutionListener implements ExecutionListener {

    private static final String GUARD_LOCAL_NAME = "guard";
    private static final String BPMN_ERROR_CODE_VAR = "__spaceBpmnErrorCode";
    private static final String BPMN_ERROR_MESSAGE_VAR = "__spaceBpmnErrorMessage";

    private final EnvironmentalTaskRegistry environmentalTaskRegistry;
    private final ParticipantService participantService;
    private final ParticipantDataService participantDataService;
    private final ParticipantTourService participantTourService;
    private final ProcessVariableService processVariableService;

    public GenericTaskExecutionListener(EnvironmentalTaskRegistry environmentalTaskRegistry,
                                        ParticipantService participantService,
                                        ParticipantDataService participantDataService,
                                        ParticipantTourService participantTourService,
                                        ProcessVariableService processVariableService) {
        this.environmentalTaskRegistry = environmentalTaskRegistry;
        this.participantService = participantService;
        this.participantDataService = participantDataService;
        this.participantTourService = participantTourService;
        this.processVariableService = processVariableService;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (EVENTNAME_START.equals(execution.getEventName())) {
            handleStart(execution);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            handleEnd(execution);
        }
    }

    private void handleStart(DelegateExecution execution) {
        initializeAttributesIfMissing(execution);

        String guardValue = extractGuardValue(execution);
        if (guardValue == null || guardValue.isBlank()) {
            return;
        }

        org.unicam.intermediate.models.Participant participant = participantService.resolveCurrentParticipant(execution);
        String participantId = participant != null ? participant.getId() : null;

        // Reuse environmental registry: guard only, no action and no timer.
        environmentalTaskRegistry.registerTask(
                execution.getId(),
                execution.getCurrentActivityId(),
                guardValue,
                null,
                participantId,
                null
        );

        log.info("[GENERIC_TASK] WAITING | Activity: {} - {} | Guard: {}",
                execution.getCurrentActivityId(),
                execution.getCurrentActivityName() != null ? execution.getCurrentActivityName() : "(unnamed)",
                guardValue);
    }

    private void handleEnd(DelegateExecution execution) {
        String bpmnErrorCode = (String) execution.getVariableLocal(BPMN_ERROR_CODE_VAR);
        String bpmnErrorMessage = (String) execution.getVariableLocal(BPMN_ERROR_MESSAGE_VAR);
        execution.removeVariableLocal(BPMN_ERROR_CODE_VAR);
        execution.removeVariableLocal(BPMN_ERROR_MESSAGE_VAR);

        if (bpmnErrorCode == null || bpmnErrorCode.isBlank()) {
            persistDataObjectIfConfigured(execution);
        }

        environmentalTaskRegistry.removeTask(execution.getId(), execution.getCurrentActivityId());

        if (bpmnErrorCode != null && !bpmnErrorCode.isBlank()) {
            throw new BpmnError(
                    bpmnErrorCode,
                    bpmnErrorMessage != null ? bpmnErrorMessage : "Task guard failed"
            );
        }
    }

    /**
     * Initializes the 'attributes' map in the execution context by retrieving saved data from the
     * {@link ParticipantDataService} without overwriting existing non-empty maps.
     */
    private void initializeAttributesIfMissing(DelegateExecution execution) {
        try {
            Object rawAttrs = execution.getVariable("attributes");
            Map<String, Object> attributes = new LinkedHashMap<>();

            // 1. If attributes already exist in Camunda context, preserve them
            if (rawAttrs instanceof Map<?, ?> existingMap && !existingMap.isEmpty()) {
                existingMap.forEach((k, v) -> attributes.put(String.valueOf(k), v));
            } else {
                // 2. Fallback: If Camunda variable is null/empty, restore saved attributes from ParticipantDataService
                org.unicam.intermediate.models.Participant currentParticipant = participantService.resolveCurrentParticipant(execution);
                if (currentParticipant != null && currentParticipant.getId() != null) {
                    participantDataService.getParticipant(currentParticipant.getId()).ifPresent(p -> {
                        if (p.getAttributes() != null && !p.getAttributes().isEmpty()) {
                            attributes.putAll(p.getAttributes());
                            log.info("[GENERIC_TASK] Initialized {} attributes from ParticipantDataService for participant '{}'",
                                    p.getAttributes().size(), p.getId());
                        }
                    });
                }
            }

            // Set the execution context process variable
            execution.setVariable("attributes", attributes);
        } catch (Exception e) {
            log.warn("[GENERIC_TASK] Could not pre-initialize attributes on start: {}", e.getMessage());
        }
    }

    /**
     * Persists generated tour calculation payload to BPMN Data Output Associations attached to the current activity.
     */
    private void persistDataObjectIfConfigured(DelegateExecution execution) {
        Activity activity = resolveBpmnActivity(execution);
        if (activity == null || activity.getDataOutputAssociations() == null || activity.getDataOutputAssociations().isEmpty()) {
            return;
        }

        org.unicam.intermediate.models.Participant currentParticipant = participantService.resolveCurrentParticipant(execution);

        TourResponse tourResponse = null;
        if (currentParticipant != null) {
            Participant pPojo = participantDataService.getParticipant(currentParticipant.getId()).orElse(null);
            if (pPojo != null) {
                tourResponse = participantTourService.calculateTour(pPojo);
            }
        }

        List<String> updatedTargetPlaceIds = tourResponse != null ? tourResponse.getTargetPlaceIds() : List.of();

        for (DataOutputAssociation association : activity.getDataOutputAssociations()) {
            String variableName = resolveOutputVariableName(association);
            if (variableName != null && !variableName.isBlank()) {

                Object existingVariable = processVariableService.getVariable(execution.getProcessInstanceId(), variableName);
                Map<String, Object> mergedValue = new LinkedHashMap<>();

                if (existingVariable instanceof Map<?, ?> existingMap) {
                    existingMap.forEach((k, v) -> mergedValue.put(String.valueOf(k), v));
                }

                mergedValue.put("targetPlaceIds", updatedTargetPlaceIds);

                if (currentParticipant != null) {
                    mergedValue.put("participantId", currentParticipant.getId());
                }

                processVariableService.setVariable(execution.getProcessInstanceId(), variableName, mergedValue);
                log.info("[GENERIC_TASK] Updated DataObject '{}' with targetPlaceIds: {}", variableName, updatedTargetPlaceIds);
            }
        }
    }

    /**
     * Resolves the underlying BPMN {@link Activity} object from the current delegate execution.
     */
    private Activity resolveBpmnActivity(DelegateExecution execution) {
        ModelElementInstance modelElement = execution.getBpmnModelElementInstance();
        if (modelElement instanceof Activity activity) {
            return activity;
        }

        try {
            BpmnModelInstance modelInstance = execution.getBpmnModelInstance();
            if (modelInstance != null && execution.getCurrentActivityId() != null) {
                ModelElementInstance foundElement = modelInstance.getModelElementById(execution.getCurrentActivityId());
                if (foundElement instanceof Activity activityElement) {
                    return activityElement;
                }
            }
        } catch (Exception e) {
            log.warn("[GENERIC_TASK] Error resolving BPMN Activity by ID: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Resolves the variable target name from a given BPMN Data Output Association.
     */
    private String resolveOutputVariableName(DataOutputAssociation association) {
        if (association == null || association.getTarget() == null) {
            return null;
        }

        if (association.getTarget() instanceof DataObjectReference dataObjectReference) {
            if (dataObjectReference.getName() != null && !dataObjectReference.getName().isBlank()) {
                return dataObjectReference.getName().trim();
            }
        }

        if (association.getTarget() instanceof FlowElement flowElement) {
            if (flowElement.getName() != null && !flowElement.getName().isBlank()) {
                return flowElement.getName().trim();
            }
            if (flowElement.getId() != null && !flowElement.getId().isBlank()) {
                return flowElement.getId().trim();
            }
        }

        return association.getTarget().getId();
    }

    private String extractGuardValue(DelegateExecution execution) {
        return extractExtensionValue(execution, GUARD_LOCAL_NAME);
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
}
