package org.unicam.intermediate.service;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.record.GatewayWaitInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ExclusiveGatewayGuardRegistry {

    private static final String SELECTED_FLOW_VAR = "selectedSequenceFlowId";
    private final Map<String, GatewayWaitInfo> activeGateways = new ConcurrentHashMap<>();

    private final RuntimeService runtimeService;
    private final SequenceFlowGuardEvaluator sequenceFlowGuardEvaluator;

    public ExclusiveGatewayGuardRegistry(RuntimeService runtimeService,
                                         SequenceFlowGuardEvaluator sequenceFlowGuardEvaluator) {
        this.runtimeService = runtimeService;
        this.sequenceFlowGuardEvaluator = sequenceFlowGuardEvaluator;
    }

    public void registerGateway(String executionId,
                                String gatewayId,
                                String processDefinitionId,
                                String participantId,
                                List<String> outgoingFlowIds) {
        if (executionId == null || gatewayId == null || processDefinitionId == null || outgoingFlowIds == null || outgoingFlowIds.isEmpty()) {
            return;
        }

        GatewayWaitInfo info = new GatewayWaitInfo(executionId, gatewayId, processDefinitionId, participantId, List.copyOf(outgoingFlowIds));
        activeGateways.put(executionId, info);

        log.info("[ExclusiveGatewayRegistry] Registered gateway {} (execution={}) with {} guarded outgoing flows",
                gatewayId, executionId, outgoingFlowIds.size());
    }

    public void removeGateway(String executionId) {
        if (executionId == null) {
            return;
        }

        GatewayWaitInfo removed = activeGateways.remove(executionId);
        if (removed != null) {
            log.info("[ExclusiveGatewayRegistry] Removed gateway {} (execution={})",
                    removed.gatewayId(), executionId);
        }
    }

    public void removeGateway(String executionId, String expectedGatewayId) {
        if (executionId == null || expectedGatewayId == null) {
            return;
        }

        GatewayWaitInfo current = activeGateways.get(executionId);
        if (current == null || !expectedGatewayId.equals(current.gatewayId())) {
            return;
        }

        if (activeGateways.remove(executionId, current)) {
            log.info("[ExclusiveGatewayRegistry] Removed gateway {} (execution={})",
                    current.gatewayId(), executionId);
        }
    }

    @Scheduled(fixedRate = 2000)
    public void checkWaitingGateways() {
        if (activeGateways.isEmpty()) {
            return;
        }

        for (GatewayWaitInfo gatewayInfo : activeGateways.values()) {
            String selectedFlowId = selectFirstSatisfiedFlow(gatewayInfo);
            if (selectedFlowId == null) {
                continue;
            }

            // Claim the gateway before signaling it. This prevents a concurrent
            // scheduler pass from signaling the same execution twice.
            if (!activeGateways.remove(gatewayInfo.executionId(), gatewayInfo)) {
                continue;
            }

            try {
                if (!hasCurrentActivity(gatewayInfo.executionId(), gatewayInfo.gatewayId())) {
                    log.debug("[ExclusiveGatewayRegistry] Removing stale gateway {} (execution={})",
                            gatewayInfo.gatewayId(), gatewayInfo.executionId());
                    continue;
                }

                runtimeService.setVariable(gatewayInfo.executionId(), SELECTED_FLOW_VAR, selectedFlowId);
                runtimeService.signal(gatewayInfo.executionId());

                log.info("[ExclusiveGatewayRegistry] Signaled gateway {} (execution={}) with selected flow {}",
                        gatewayInfo.gatewayId(), gatewayInfo.executionId(), selectedFlowId);
            } catch (Exception e) {
                if (isStaleExecutionError(e)
                        || !hasCurrentActivity(gatewayInfo.executionId(), gatewayInfo.gatewayId())) {
                    log.debug("[ExclusiveGatewayRegistry] Gateway {} (execution={}) is no longer signalable: {}",
                            gatewayInfo.gatewayId(), gatewayInfo.executionId(), e.getMessage());
                } else {
                    activeGateways.putIfAbsent(gatewayInfo.executionId(), gatewayInfo);
                    log.error("[ExclusiveGatewayRegistry] Failed to signal gateway {} (execution={}): {}",
                            gatewayInfo.gatewayId(), gatewayInfo.executionId(), e.getMessage(), e);
                }
            }
        }
    }

    private boolean hasCurrentActivity(String executionId, String activityId) {
        try {
            return runtimeService.createExecutionQuery()
                    .executionId(executionId)
                    .activityId(activityId)
                    .singleResult() != null;
        } catch (Exception e) {
            log.debug("[ExclusiveGatewayRegistry] Could not inspect execution {}: {}",
                    executionId, e.getMessage());
            return false;
        }
    }

    private boolean isStaleExecutionError(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        String normalized = message.toLowerCase();
        return normalized.contains("has no current activity")
                || normalized.contains("execution is null")
                || normalized.contains("doesn't exist")
                || normalized.contains("not found");
    }

    private String selectFirstSatisfiedFlow(GatewayWaitInfo gatewayInfo) {
        for (String flowId : gatewayInfo.outgoingFlowIds()) {
            boolean satisfied = sequenceFlowGuardEvaluator.evaluateGuard(
                    gatewayInfo.processDefinitionId(),
                    flowId,
                    gatewayInfo.participantId(),
                    gatewayInfo.executionId());
            if (satisfied) {
                return flowId;
            }
        }

        return null;
    }
}
