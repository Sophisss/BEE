package org.unicam.intermediate.service;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.unicam.intermediate.events.ParticipantPositionChangedEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class EnvironmentChangeNotifier {

    private static final String PARTICIPANT_ID_VARIABLE = "participantId";
    private static final String ENVIRONMENT_REVISION_VARIABLE = "__spaceEnvironmentRevision";

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    public EnvironmentChangeNotifier(RuntimeService runtimeService,
                                     RepositoryService repositoryService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
    }

    @EventListener
    public void onParticipantPositionChanged(ParticipantPositionChangedEvent event) {
        if (event == null
                || event.participantId() == null
                || event.participantId().isBlank()
                || java.util.Objects.equals(event.oldPosition(), event.newPosition())) {
            return;
        }

        notifyAllActiveProcessInstances();
    }

    /**
     * A position change affects every conditional event that may resolve
     * myPlace() to the changed physical place. Such processes do not
     * necessarily share a business key with the process receiving the event.
     */
    private void notifyAllActiveProcessInstances() {
        List<ProcessInstance> activeInstances = runtimeService.createProcessInstanceQuery()
                .active()
                .list();

        for (ProcessInstance instance : activeInstances) {
            notifyProcessInstanceEnvironmentChanged(instance);
        }

        log.info("[EnvironmentChangeNotifier] Position change propagated | activeProcessInstances={}",
                activeInstances.size());
    }

    public void notifyProcessInstanceChanged(String processInstanceId, String participantId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return;
        }

        ProcessInstance source = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (source == null) {
            log.warn("[EnvironmentChangeNotifier] Process instance {} not found", processInstanceId);
            return;
        }

        List<String> processInstanceIds = findRelatedProcessInstances(source);
        for (String relatedProcessInstanceId : processInstanceIds) {
            ProcessInstance related = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(relatedProcessInstanceId)
                    .singleResult();
            if (related == null) {
                continue;
            }

            notifyProcessInstanceEnvironmentChanged(related);
        }

        log.info("[EnvironmentChangeNotifier] Published environment change | processInstances={} | participantId={} | businessKey={}",
                processInstanceIds.size(), participantId, source.getBusinessKey());
    }

    private void notifyProcessInstanceEnvironmentChanged(ProcessInstance instance) {
        if (instance == null) {
            return;
        }

        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put(ENVIRONMENT_REVISION_VARIABLE, System.nanoTime());

        String mappedParticipantId = resolveParticipantId(instance, instance);
        if (mappedParticipantId != null) {
            variables.put(PARTICIPANT_ID_VARIABLE, mappedParticipantId);
        }

        runtimeService.setVariables(instance.getId(), variables);
    }

    private List<String> findRelatedProcessInstances(ProcessInstance source) {
        Set<String> ids = new HashSet<>();
        ids.add(source.getId());

        if (source.getBusinessKey() != null && !source.getBusinessKey().isBlank()) {
            runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(source.getBusinessKey())
                    .active()
                    .list()
                    .forEach(instance -> ids.add(instance.getId()));
        }

        BpmnModelInstance model = repositoryService.getBpmnModelInstance(source.getProcessDefinitionId());
        if (model == null) {
            return new ArrayList<>(ids);
        }

        for (Collaboration collaboration : model.getModelElementsByType(Collaboration.class)) {
            for (Participant participant : collaboration.getParticipants()) {
                Process process = participant.getProcess();
                if (process == null || process.getId() == null) {
                    continue;
                }
                runtimeService.createProcessInstanceQuery()
                        .processDefinitionKey(process.getId())
                        .active()
                        .list()
                        .forEach(instance -> ids.add(instance.getId()));
            }
        }

        return new ArrayList<>(ids);
    }

    private String resolveParticipantId(ProcessInstance source, ProcessInstance related) {
        BpmnModelInstance model = repositoryService.getBpmnModelInstance(source.getProcessDefinitionId());
        if (model == null) {
            return null;
        }

        String relatedProcessKey = repositoryService
                .getProcessDefinition(related.getProcessDefinitionId())
                .getKey();
        for (Collaboration collaboration : model.getModelElementsByType(Collaboration.class)) {
            for (Participant participant : collaboration.getParticipants()) {
                Process process = participant.getProcess();
                if (process != null
                        && process.getId().equals(relatedProcessKey)) {
                    return participant.getId();
                }
            }
        }
        return null;
    }
}
