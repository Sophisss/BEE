package org.unicam.intermediate.service;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service("conditionalEventGuardEvaluator")
@Slf4j
public class ConditionalEventGuardEvaluator {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;
    private final ParticipantService participantService;
    private final ConcurrentMap<String, Boolean> previousResults = new ConcurrentHashMap<>();

    public ConditionalEventGuardEvaluator(EnvironmentDataService environmentDataService,
                                          ParticipantDataService participantDataService,
                                          ParticipantService participantService) {
        this.environmentDataService = environmentDataService;
        this.participantDataService = participantDataService;
        this.participantService = participantService;
    }

    /**
     * Evaluate the expression of the Conditional Event by first resolving myPlace()
     */
    public boolean evaluate(DelegateExecution execution, String rawExpression) {
        if (execution == null || rawExpression == null || rawExpression.isBlank()) {
            return false;
        }

        // Retrieve the participantId from the execution
        String participantId = null;
        Object runtimeParticipant = execution.getVariable("participantId");
        if (runtimeParticipant != null) {
            participantId = String.valueOf(runtimeParticipant);
        }
        if (participantId == null || participantId.isBlank()) {
            participantId = participantService.resolveParticipantFromExecution(execution);
            log.debug("[ConditionalEventEvaluator] participantId risolto dal BPMN: {} per {}",
                    participantId, execution.getCurrentActivityId());
        }

        // Resolve myPlace() using the current position of the participant
        String resolvedExpression = resolveMyPlace(
                rawExpression,
                execution.getProcessDefinitionId(),
                participantId,
                execution.getCurrentActivityId()
        );

        // If myPlace() is still present in the resolved expression, it means we are waiting for the participant's position to be available
        if (rawExpression.contains("myPlace()") && resolvedExpression.contains("myPlace()")) {
            log.debug("[ConditionalEventEvaluator] In attesa della posizione del partecipante per risolvere myPlace() su {}", execution.getCurrentActivityId());
            return false;
        }

        // Evaluate the resolved expression against the current state of the environment
        boolean result = evaluateCondition(resolvedExpression, execution.getCurrentActivityId());
        return evaluateOnTransition(execution, resolvedExpression, result);
    }

    private boolean evaluateOnTransition(DelegateExecution execution, String expression, boolean result) {
        String key = execution.getProcessInstanceId()
                + "|" + execution.getCurrentActivityId()
                + "|" + expression;
        Boolean previous = previousResults.put(key, result);
        return result && !Boolean.TRUE.equals(previous);
    }

    private String resolveMyPlace(String expression, String processDefinitionId, String participantId, String sourceId) {
        if (expression == null || !expression.contains("myPlace()")) {
            return expression;
        }

        if (participantId == null || participantId.isBlank()) {
            log.warn("[ConditionalEventEvaluator] myPlace() usato ma participantId non disponibile per {}", sourceId);
            return expression;
        }

        Optional<Participant> participantOpt = participantDataService.getParticipant(participantId);

        if (participantOpt.isEmpty()) {
            String participantName = participantService.getParticipantName(processDefinitionId, participantId);
            if (participantName != null && !participantName.equals(participantId)) {
                participantOpt = participantDataService.getParticipantByName(participantName);
            }
        }

        return participantOpt
                .map(participant -> {
                    String position = participant.getPosition();
                    if (position == null || position.isBlank()) {
                        log.warn("[ConditionalEventEvaluator] myPlace() usato ma il partecipante '{}' non ha posizione per {}", participantId, sourceId);
                        return expression;
                    }
                    return expression.replace("myPlace()", position);
                })
                .orElseGet(() -> {
                    log.warn("[ConditionalEventEvaluator] myPlace() usato ma partecipante '{}' non trovato per {}", participantId, sourceId);
                    return expression;
                });
    }

    private boolean evaluateCondition(String resolvedExpression, String activityId) {

        try {
            int dotIndex = resolvedExpression.indexOf('.');
            if (dotIndex <= 0 || dotIndex == resolvedExpression.length() - 1) {
                return false;
            }

            String placeId = resolvedExpression.substring(0, dotIndex).trim();
            String conditionStr = resolvedExpression.substring(dotIndex + 1).trim();
            String attributeKey = extractAttributeKey(conditionStr);

            Optional<Object> attrValueOpt = environmentDataService.getPhysicalPlaceAttribute(placeId, attributeKey);

            if (attrValueOpt.isEmpty()) {
                return false;
            }

            Object actualValue = attrValueOpt.get();
            String actualText = String.valueOf(actualValue).trim();
            log.info("[DEBUG-CONDITIONAL] Place: '{}' | Attribute: '{}' | Actual Value: '{}' | Condition: '{}'",
                    placeId, attributeKey, actualText, conditionStr);

            if (conditionStr.contains("presence_count > 0")) {
                double count = Double.parseDouble(actualText);
                return count > 0;
            }

            if (conditionStr.contains("presence_count == 0")) {
                double count = Double.parseDouble(actualText);
                return count == 0;
            }

            if (conditionStr.contains("status")) {
                if (conditionStr.contains("visit")) {
                    return "visit".equalsIgnoreCase(actualText);
                }
                if (conditionStr.contains("idle")) {
                    return "idle".equalsIgnoreCase(actualText);
                }
            }

        } catch (Exception e) {
            return false;
        }

        return false;
    }

    private String extractAttributeKey(String conditionStr) {
        if (conditionStr.contains("presence_count")) return "presence_count";
        if (conditionStr.contains("status")) return "status";
        if (conditionStr.contains("lights")) return "lights";
        if (conditionStr.contains("audio")) return "audio";
        return conditionStr.split("[=>!< ]")[0];
    }
}