package org.unicam.intermediate.service;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.service.environmental.EnvironmentDataService;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service("conditionalEventGuardEvaluator")
@Slf4j
public class ConditionalEventGuardEvaluator {

    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "^(.+?)\\.([A-Za-z0-9_-]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );
    private static final Pattern ZONE_QUANTIFIER_PATTERN = Pattern.compile(
            "^(exist|for\\s+all)\\s+([A-Za-z0-9_]+)\\s+in\\s*\\(\\s*myPlace\\(\\)\\.zone\\s*\\)\\s+such\\s+that\\s+\\2\\.([A-Za-z0-9_-]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

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

        Boolean zoneResult = evaluateZoneQuantifier(rawExpression, participantId, execution.getCurrentActivityId());
        if (zoneResult != null) {
            return evaluateOnTransition(execution, rawExpression, zoneResult);
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
            return false;
        }

        // Evaluate the resolved expression against the current state of the environment
        boolean result = evaluateCondition(resolvedExpression, execution.getCurrentActivityId());
        return evaluateOnTransition(execution, resolvedExpression, result);
    }

    private Boolean evaluateZoneQuantifier(String expression, String participantId, String activityId) {
        Matcher matcher = ZONE_QUANTIFIER_PATTERN.matcher(expression.trim());
        if (!matcher.matches()) {
            return null;
        }

        Optional<String> position = participantDataService.getParticipant(participantId)
                .map(Participant::getPosition)
                .filter(value -> value != null && !value.isBlank());
        if (position.isEmpty()) {
            return false;
        }

        String quantifier = matcher.group(1).replaceAll("\\s+", " ").toLowerCase();
        String attribute = matcher.group(3);
        String operator = matcher.group(4);
        String expected = unquote(matcher.group(5).trim());
        var places = environmentDataService.getPhysicalPlacesInSameLogicalPlace(position.get());

        if (places.isEmpty()) {
            log.warn("[ConditionalEventEvaluator] No physical places found for zone condition '{}' | activity={}",
                    expression, activityId);
            return false;
        }

        boolean result = "exist".equals(quantifier)
                ? places.stream().anyMatch(place -> matches(place.getAttributes(), attribute, operator, expected))
                : places.stream().allMatch(place -> matches(place.getAttributes(), attribute, operator, expected));
        log.debug("[ConditionalEventEvaluator] Zone condition '{}' evaluated to {} | activity={}",
                expression, result, activityId);
        return result;
    }

    private boolean matches(java.util.Map<String, Object> attributes,
                            String attribute,
                            String operator,
                            String expected) {
        if (attributes == null || !attributes.containsKey(attribute)) {
            return false;
        }
        return compare(attributes.get(attribute), operator, expected);
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
                        return expression;
                    }
                    return expression.replace("myPlace()", position);
                })
                .orElseGet(() -> {
                    return expression;
                });
    }

    private boolean evaluateCondition(String resolvedExpression, String activityId) {

        try {
            String expression = resolvedExpression.trim();
            if (expression.contains("&&")) {
                return Arrays.stream(expression.split("\\s*&&\\s*"))
                        .map(String::trim)
                        .filter(part -> !part.isBlank())
                        .allMatch(part -> evaluateCondition(part, activityId));
            }
            if (expression.contains("||")) {
                return Arrays.stream(expression.split("\\s*\\|\\|\\s*"))
                        .map(String::trim)
                        .filter(part -> !part.isBlank())
                        .anyMatch(part -> evaluateCondition(part, activityId));
            }

            Matcher matcher = CONDITION_PATTERN.matcher(expression);
            if (!matcher.matches()) {
                return false;
            }

            String placeId = matcher.group(1).trim();
            String attributeKey = matcher.group(2);
            String operator = matcher.group(3);
            String expectedText = unquote(matcher.group(4).trim());

            Optional<String> logicalPlaceId = environmentDataService.resolveLogicalPlaceId(placeId);
            if (logicalPlaceId.isPresent()
                    && ("count".equalsIgnoreCase(attributeKey) || "size".equalsIgnoreCase(attributeKey))) {
                int actualCount = environmentDataService.countPhysicalPlacesInLogicalPlace(logicalPlaceId.get());
                log.info("[DEBUG-CONDITIONAL] Logical place: '{}' | Attribute: '{}' | Actual Value: '{}' | Condition: '{}'",
                        placeId, attributeKey, actualCount, resolvedExpression);
                return compare(actualCount, operator, expectedText);
            }

            Optional<Object> attrValueOpt = environmentDataService.getPhysicalPlaceAttribute(placeId, attributeKey);

            if (attrValueOpt.isEmpty()) {
                return false;
            }

            Object actualValue = attrValueOpt.get();
            String actualText = String.valueOf(actualValue).trim();
            log.info("[DEBUG-CONDITIONAL] Place: '{}' | Attribute: '{}' | Actual Value: '{}' | Condition: '{}'",
                    placeId, attributeKey, actualText, resolvedExpression);

            Matcher attributeReference = CONDITION_PATTERN.matcher(expectedText);
            if (attributeReference.matches()) {
                expectedText = environmentDataService
                        .getPhysicalPlaceAttribute(attributeReference.group(1).trim(), attributeReference.group(2))
                        .map(String::valueOf)
                        .orElse(expectedText);
            }

            return compare(actualValue, operator, expectedText);

        } catch (Exception e) {
            log.warn("[ConditionalEventEvaluator] Could not evaluate '{}' for activity '{}': {}",
                    resolvedExpression, activityId, e.getMessage());
            return false;
        }
    }

    private boolean compare(Object actualValue, String operator, String expectedText) {
        if (actualValue == null) {
            return "!=".equals(operator) && !"null".equalsIgnoreCase(expectedText);
        }

        String actualText = String.valueOf(actualValue).trim();
        if (isBoolean(actualText) && isBoolean(expectedText)) {
            boolean actual = Boolean.parseBoolean(actualText);
            boolean expected = Boolean.parseBoolean(expectedText);
            return "==".equals(operator) ? actual == expected
                    : "!=".equals(operator) && actual != expected;
        }

        try {
            double actualNumber = Double.parseDouble(actualText);
            double expectedNumber = Double.parseDouble(expectedText);
            return switch (operator) {
                case "==" -> Double.compare(actualNumber, expectedNumber) == 0;
                case "!=" -> Double.compare(actualNumber, expectedNumber) != 0;
                case ">" -> actualNumber > expectedNumber;
                case "<" -> actualNumber < expectedNumber;
                case ">=" -> actualNumber >= expectedNumber;
                case "<=" -> actualNumber <= expectedNumber;
                default -> false;
            };
        } catch (NumberFormatException ignored) {
            int comparison = actualText.compareToIgnoreCase(expectedText);
            return switch (operator) {
                case "==" -> comparison == 0;
                case "!=" -> comparison != 0;
                default -> false;
            };
        }
    }

    private boolean isBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}