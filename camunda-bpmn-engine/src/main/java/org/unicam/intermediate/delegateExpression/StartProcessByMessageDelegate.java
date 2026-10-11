package org.unicam.intermediate.delegateExpression;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.camunda.bpm.engine.impl.cfg.TransactionState;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.service.participant.ParticipantDataService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component("startProcessByMessageDelegate")
@Slf4j
@RequiredArgsConstructor
public class StartProcessByMessageDelegate implements JavaDelegate {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final ParticipantDataService participantDataService;

    @Setter
    private Expression messageNameExpr;

    @Setter
    private Expression businessKeyExpr;

    @Override
    public void execute(DelegateExecution execution) {
        String messageName = getStringValue(messageNameExpr, execution);
        if (messageName == null || messageName.isBlank()) {
            throw new BpmnError("StartProcessByMessageError", "Missing messageName for message start");
        }

        String businessKey = getStringValue(businessKeyExpr, execution);
        if (businessKey == null || businessKey.isBlank()) {
            businessKey = execution.getBusinessKey();
        }

        final String resolvedMessageName = messageName;
        final String resolvedBusinessKey = businessKey;
        final Map<String, Object> currentExecutionVariables = new HashMap<>(execution.getVariables());

        var commandContext = Context.getCommandContext();
        if (commandContext != null && commandContext.getTransactionContext() != null) {
            commandContext.getTransactionContext().addTransactionListener(
                    TransactionState.COMMITTED,
                    command -> {
                        Map<String, Object> freshVariables = prepareProcessVariables(currentExecutionVariables, resolvedBusinessKey);
                        startProcessByMessage(resolvedMessageName, resolvedBusinessKey, freshVariables);
                    }
            );

            log.info("[StartByMessage] Scheduled process start by message '{}' after commit (businessKey='{}')",
                    resolvedMessageName, resolvedBusinessKey);
            return;
        }

        Map<String, Object> freshVariables = prepareProcessVariables(currentExecutionVariables, resolvedBusinessKey);
        startProcessByMessage(resolvedMessageName, resolvedBusinessKey, freshVariables);
    }

    /**
     * Prepares process variables dynamically for the target process instance.
     * Integrates existing variables with fresh participant attributes fetched from the data service.
     *
     * @param baseVariables initial execution variables map
     * @param businessKey   the resolved process business key
     * @return a map populated with merged process variables and participant attributes
     */
    private Map<String, Object> prepareProcessVariables(Map<String, Object> baseVariables, String businessKey) {
        Map<String, Object> variables = new HashMap<>(baseVariables);

        // Identify the participant ID (from process variables or fallback to businessKey)
        String participantId = (String) variables.get("participantId");
        if (participantId == null || participantId.isBlank()) {
            participantId = businessKey;
            variables.put("participantId", participantId);
        }

        Map<String, Object> attributesMap = new LinkedHashMap<>();
        if (variables.get("attributes") instanceof Map<?, ?> existingAttrs) {
            existingAttrs.forEach((k, v) -> attributesMap.put(String.valueOf(k), v));
        }

        if (participantId != null && !participantId.isBlank()) {
            Optional<Participant> participantOpt = participantDataService.getParticipant(participantId);
            if (participantOpt.isPresent()) {
                Participant participant = participantOpt.get();
                if (participant.getAttributes() != null && !participant.getAttributes().isEmpty()) {
                    // Dynamically merge all participant attributes, preserving existing keys
                    participant.getAttributes().forEach(attributesMap::putIfAbsent);
                    log.info("[StartByMessage] Loaded FRESH attributes for participant '{}': {}",
                            participantId, participant.getAttributes());
                } else {
                    log.warn("[StartByMessage] Participant '{}' found, but has empty attributes in DB", participantId);
                }
            } else {
                log.warn("[StartByMessage] Participant '{}' not found in ParticipantDataService", participantId);
            }
        }

        variables.put("attributes", attributesMap);
        variables.putAll(attributesMap);

        return variables;
    }

    /**
     * Starts a new process instance by message, ensuring that no active instance with the same businessKey exists.
     * @param messageName the message start event name
     * @param businessKey the business key for the process instance
     * @param variables the process variables to be passed to the new instance
     */
    private void startProcessByMessage(String messageName, String businessKey, Map<String, Object> variables) {
        if (businessKey == null || businessKey.isBlank()) {
            runtimeService.startProcessInstanceByMessage(messageName, variables);
            log.info("[StartByMessage] Started process by message '{}' without businessKey", messageName);
            return;
        }

        if (hasActiveMessageStartedProcess(messageName, businessKey)) {
            log.info("[StartByMessage] Skipped message '{}' because a target process is already active with businessKey '{}'",
                    messageName, businessKey);
            return;
        }

        runtimeService.startProcessInstanceByMessage(messageName, businessKey, variables);
        log.info("[StartByMessage] Started process by message '{}' with businessKey '{}'",
                messageName, businessKey);
    }

    /**
     * Checks if there is already an active process instance started by the specified message and businessKey.
     *
     * @param messageName the message start event name
     * @param businessKey the target business key
     * @return {@code true} if an active instance exists, {@code false} otherwise
     */
    private boolean hasActiveMessageStartedProcess(String messageName, String businessKey) {
        // Query for all active process definitions that have a message start event with the given message name
        List<ProcessDefinition> targetDefinitions = repositoryService.createProcessDefinitionQuery()
                .messageEventSubscriptionName(messageName)
                .latestVersion()
                .active()
                .list();

        // Check if any of the target process definitions have an active instance with the same business key
        for (ProcessDefinition definition : targetDefinitions) {
            long activeInstances = runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(definition.getKey())
                    .processInstanceBusinessKey(businessKey)
                    .active()
                    .count();

            if (activeInstances > 0) {
                return true;
            }
        }

        return false;
    }

    private String getStringValue(Expression expression, DelegateExecution execution) {
        if (expression == null) {
            return null;
        }

        Object value = expression.getValue(execution);
        return value != null ? value.toString() : null;
    }
}