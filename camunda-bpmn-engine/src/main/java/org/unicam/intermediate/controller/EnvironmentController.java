package org.unicam.intermediate.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.unicam.intermediate.models.dto.Response;
import org.unicam.intermediate.models.pojo.Edge;
import org.unicam.intermediate.models.pojo.LogicalPlace;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.models.dto.TourResponse;
import org.unicam.intermediate.service.environmental.*;
import org.unicam.intermediate.service.environmental.binding.BindingTaskRegistry;
import org.unicam.intermediate.service.environmental.movement.MovementTaskRegistry;
import org.unicam.intermediate.service.environmental.unbinding.UnbindingTaskRegistry;
import org.unicam.intermediate.service.participant.ParticipantDataService;
import org.unicam.intermediate.service.participant.ParticipantService;

import java.util.*;

@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
@Slf4j
public class EnvironmentController {

    private final EnvironmentDataService environmentDataService;
    private final ParticipantDataService participantDataService;
    private final EnvironmentalTaskRegistry environmentalTaskRegistry;
    private final MovementTaskRegistry movementTaskRegistry;
    private final BindingTaskRegistry bindingTaskRegistry;
    private final UnbindingTaskRegistry unbindingTaskRegistry;
    private final ParticipantTourService participantTourService;
    private final SensorDataService sensorDataService;
    private final TaskService taskService;
    private final ParticipantService participantService;
    private final RuntimeService runtimeService;
    private final BoundParticipantsDiscordanceMonitor boundParticipantsMonitor;

    @GetMapping("/reload")
    public ResponseEntity<Response<String>> reloadEnvironmentGet() {
        return reloadEnvironmentInternal();
    }

    @PostMapping("/reload")
    public ResponseEntity<Response<String>> reloadEnvironmentPost() {
        return reloadEnvironmentInternal();
    }

    private ResponseEntity<Response<String>> reloadEnvironmentInternal() {
        try {
            environmentDataService.reloadEnvironment();
            return ResponseEntity.ok(Response.ok("Environment reloaded"));
        } catch (Exception e) {
            log.error("[Environment API] Failed to reload environment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to reload environment: " + e.getMessage()));
        }
    }

    @GetMapping("/pp")
    public ResponseEntity<Response<List<PhysicalPlace>>> getPhysicalPlaces() {
        try {
            List<PhysicalPlace> physicalPlaces = environmentDataService.getPhysicalPlaces();
            return ResponseEntity.ok(Response.ok(physicalPlaces));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve physical places", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve physical places: " + e.getMessage()));
        }
    }

    @GetMapping("/lp")
    public ResponseEntity<Response<List<LogicalPlace>>> getLogicalPlaces() {
        try {
            List<LogicalPlace> logicalPlaces = environmentDataService.getLogicalPlaces();
            return ResponseEntity.ok(Response.ok(logicalPlaces));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve logical places", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve logical places: " + e.getMessage()));
        }
    }

    @GetMapping("/edges")
    public ResponseEntity<Response<List<Edge>>> getEdges() {
        try {
            List<Edge> edges = environmentDataService.getEdges();
            return ResponseEntity.ok(Response.ok(edges));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve edges", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve edges: " + e.getMessage()));
        }
    }

    @GetMapping("/pp/{id}")
    public ResponseEntity<Response<PhysicalPlace>> getPhysicalPlaceById(@PathVariable String id) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }
            return ResponseEntity.ok(Response.ok(place.get()));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve physical place with id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve physical place: " + e.getMessage()));
        }
    }

    @GetMapping("/pp/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> getAttributeByKey(@PathVariable String id, @PathVariable String key) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }

            if (!environmentDataService.hasPhysicalPlaceAttribute(id, key)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Attribute not found: " + key));
            }

            Optional<Object> attributeValue = environmentDataService.getPhysicalPlaceAttribute(id, key);
            return ResponseEntity.ok(Response.ok(attributeValue.orElse(null)));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve attribute '{}' for place: {}", key, id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve attribute: " + e.getMessage()));
        }
    }

    @PutMapping("/pps/{id}/attributes/{key}")
    public ResponseEntity<Response<Object>> setAttributeByKey(@PathVariable String id, @PathVariable String key,
                                                              @RequestBody Map<String, Object> requestBody) {
        try {
            Optional<PhysicalPlace> place = environmentDataService.getPhysicalPlace(id);
            if (place.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }
            Object newValue = requestBody.get("value");
            place.get().getAttributes().put(key, newValue);
            log.info("[Environment API] Attribute '{}' for place '{}' set to: {}", key, id, newValue);
            return ResponseEntity.ok(Response.ok(newValue));
        } catch (Exception e) {
            log.error("[Environment API] Failed to set attribute '{}' for place: {}", key, id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to set attribute: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the list of all participants, merging their local attributes with any attributes stored in the Camunda process variables.
     *
     * @return A ResponseEntity containing the list of participants or an error message if an error occurs.
     */
    @GetMapping("/participants")
    public ResponseEntity<Response<List<Participant>>> getParticipants() {
        try {
            List<Participant> participants = participantDataService.getParticipants();

            for (Participant p : participants) {
                if (p.getAttributes() == null) {
                    p.setAttributes(new LinkedHashMap<>());
                }

                Task activeTask = findActiveUserTaskForParticipant(p.getId());
                if (activeTask != null) {
                    Map<String, Object> executionVars = runtimeService.getVariables(activeTask.getProcessInstanceId());
                    Object attrsObj = executionVars.get("attributes");
                    if (attrsObj instanceof Map<?, ?> camundaAttrs) {
                        camundaAttrs.forEach((k, v) -> p.getAttributes().putIfAbsent(String.valueOf(k), v));
                    }
                }
            }

            return ResponseEntity.ok(Response.ok(participants));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve participants", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve participants: " + e.getMessage()));
        }
    }

    @GetMapping("/participants/{id}/position")
    public ResponseEntity<Response<String>> getParticipantPosition(@PathVariable String id) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }
            String position = participant.get().getPosition();
            return ResponseEntity.ok(Response.ok(position));
        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve position for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve position: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the list of pending actions for a specific participant.
     *
     * @param id The ID of the participant.
     * @return A ResponseEntity containing the list of pending actions or an error message if an error occurs.
     */
    @GetMapping("/participants/{id}/actions/pending")
    public ResponseEntity<Response<List<Map<String, String>>>> getPendingParticipantActions(
            @PathVariable String id) {

        try {
            Optional<Participant> participantOpt = participantDataService.getParticipant(id);

            if (participantOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error("Participant not found: " + id));
            }

            Participant participant = participantOpt.get();

            // If the participant's position is null or blank, return an empty list of pending actions
            if (participant.getPosition() == null || participant.getPosition().isBlank()) {
                return ResponseEntity.ok(Response.ok(List.of()));
            }

            List<String> boundPartners = boundParticipantsMonitor.getBoundPartners(id);
            TourResponse calculatedTour;

            // If the participant is bound to others, calculate the tour based on the first bound partner,
            // otherwise calculate the tour based on the participant itself
            if (!boundPartners.isEmpty()) {
                String guideId = boundPartners.get(0);
                Participant guide = participantDataService.getParticipant(guideId).orElse(participant);
                calculatedTour = participantTourService.calculateTour(guide);
            } else {
                calculatedTour = participantTourService.calculateTour(participant);
            }

            String targetId = (calculatedTour != null && calculatedTour.getTargetPlaceIds() != null && !calculatedTour.getTargetPlaceIds().isEmpty())
                    ? calculatedTour.getTargetPlaceIds().get(0)
                    : null;

            Task activeTask = findActiveUserTaskForParticipant(id);
            Set<String> targetIds = new LinkedHashSet<>();
            targetIds.add(id);

            if (activeTask != null) {
                String resolvedId = participantService.resolveParticipantForTask(activeTask);
                if (resolvedId != null && !resolvedId.isBlank()) {
                    targetIds.add(resolvedId);
                }
            }

            List<Map<String, String>> pendingActions = new ArrayList<>();
            List<Map<String, String>> pendingMovements = new ArrayList<>();

            // Aggregate pending actions and movements for the participant and any resolved participants
            for (String targetIdCandidate : targetIds) {
                pendingActions.addAll(environmentalTaskRegistry.getPendingActionsForParticipant(targetIdCandidate));
                pendingActions.addAll(bindingTaskRegistry.getPendingBindingsForParticipant(targetIdCandidate));
                pendingActions.addAll(unbindingTaskRegistry.getPendingUnbindingsForParticipant(targetIdCandidate));
                pendingMovements.addAll(movementTaskRegistry.getPendingMovementsForParticipant(targetIdCandidate));
            }

            if (targetId == null && !pendingMovements.isEmpty()) {
                targetId = pendingMovements.get(0).get("destination");
            }

            if (targetId == null) {
                return ResponseEntity.ok(Response.ok(pendingActions));
            }

            Optional<PhysicalPlace> realPlaceOpt = environmentDataService.getPhysicalPlace(targetId);
            String dynamicDisplayName = realPlaceOpt.map(PhysicalPlace::getName).orElse(targetId);


            List<Map<String, String>> finalPendingActions = new ArrayList<>(pendingActions);
            if (!pendingMovements.isEmpty()) {
                Map<String, String> updatedMovement = new HashMap<>(pendingMovements.get(0));
                String messageText = "Move to " + dynamicDisplayName;
                updatedMovement.put("destination", targetId);
                updatedMovement.put("message", messageText);
                updatedMovement.put("targetPlaceId", targetId);
                updatedMovement.put("description", messageText);
                finalPendingActions.add(updatedMovement);
            }

            return ResponseEntity.ok(Response.ok(finalPendingActions));

        } catch (Exception e) {
            log.error("[Environment API] Failed to retrieve pending actions for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to retrieve pending actions: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the calculated path for a specific participant.
     *
     * @param id The ID of the participant for whom to retrieve the tour.
     * @return A ResponseEntity containing the path information or an error message if the participant is not found
     * or if an error occurs during calculation.
     */
    @GetMapping("/participants/{id}/tour")
    public ResponseEntity<Response<TourResponse>> getParticipantTour(@PathVariable String id) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }

            Participant p = participant.get();

            // 1. Identify the active Process Instance associated with the current participant
            Task activeTask = findActiveUserTaskForParticipant(id);
            String processInstanceId = activeTask != null ? activeTask.getProcessInstanceId() : null;

            if (processInstanceId == null) {
                processInstanceId = runtimeService.createProcessInstanceQuery()
                        .active()
                        .variableValueEquals("participantId", id)
                        .list()
                        .stream()
                        .findFirst()
                        .map(ProcessInstance::getProcessInstanceId)
                        .orElse(null);
            }

            TourResponse tour;
            if (processInstanceId != null) {
                tour = participantTourService.calculateAndSyncTour(p, processInstanceId);
            } else {
                tour = participantTourService.calculateTour(p);
            }

            // 2. If the primary tour is empty, attempt to resolve a shared tour from other participants in the same process context
            if (isTourEmpty(tour)) {
                Set<String> candidateIds = new LinkedHashSet<>();

                if (processInstanceId != null) {
                    List<Task> processTasks = taskService.createTaskQuery()
                            .processInstanceId(processInstanceId)
                            .list();
                    for (Task task : processTasks) {
                        String resolvedId = participantService.resolveParticipantForTask(task);
                        if (resolvedId != null && !resolvedId.equals(id)) {
                            candidateIds.add(resolvedId);
                        }
                    }
                }

                for (Participant otherP : participantDataService.getParticipants()) {
                    if (!otherP.getId().equals(id)) {
                        candidateIds.add(otherP.getId());
                    }
                }

                for (String candidateId : candidateIds) {
                    Optional<Participant> candidateOpt = participantDataService.getParticipant(candidateId);
                    if (candidateOpt.isPresent()) {
                        TourResponse candidateTour = processInstanceId != null
                                ? participantTourService.calculateAndSyncTour(candidateOpt.get(), processInstanceId)
                                : participantTourService.calculateTour(candidateOpt.get());

                        if (!isTourEmpty(candidateTour)) {
                            tour = candidateTour;
                            break;
                        }
                    }
                }
            }

            return ResponseEntity.ok(Response.ok(tour));
        } catch (Exception e) {
            log.error("[Environment API] Failed to calculate tour for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to calculate tour: " + e.getMessage()));
        }
    }

    /**
     * Endpoint for notification of closed places or environmental events.
     */
    @PostMapping("/re-route/environment-change")
    public ResponseEntity<TourResponse> handleEnvironmentChange(
            @RequestBody Participant participant,
            @RequestParam String closedPlaceId) {

        TourResponse newTour = participantTourService.handleEnvironmentalChange(participant, closedPlaceId);
        return ResponseEntity.ok(newTour);
    }

    /**
     * Completes the active User Task for a participant, merging local attributes with Camunda process variables.
     *
     * @param id The ID of the participant for whom to complete the task.
     * @return A ResponseEntity containing the result of the operation or an error message if the participant is not found or if an error occurs.
     */
    @PostMapping("/participants/{id}/configure/complete")
    public ResponseEntity<Response<Map<String, Object>>> completeConfigureTask(@PathVariable String id) {
        try {
            Optional<Participant> participantOpt = participantDataService.getParticipant(id);
            if (participantOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }

            Participant participant = participantOpt.get();

            // 1. Search for active User Task assigned to the participant
            Task task = findActiveUserTaskForParticipant(id);

            if (task == null) {
                log.warn("[TaskComplete] No active task found for participant '{}'", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("No active User Task found for participant: " + id));
            }

            String processInstanceId = task.getProcessInstanceId();

            // 2. Prepare variables by combining local participant POJO attributes and current Camunda process variables
            Map<String, Object> taskVariables = new LinkedHashMap<>();

            // Read existing attributes stored in the Camunda instance
            Object existingCamundaAttrs = runtimeService.getVariable(processInstanceId, "attributes");
            if (existingCamundaAttrs instanceof Map<?, ?> camundaMap) {
                camundaMap.forEach((k, v) -> taskVariables.put(String.valueOf(k), v));
            }

            // Override/merge with the participant's updated local attributes
            if (participant.getAttributes() != null) {
                taskVariables.putAll(participant.getAttributes());
            }

            taskVariables.put("attributes", new LinkedHashMap<>(taskVariables));
            taskVariables.put("participantId", id);

            // 3. Set process variables and complete the User Task
            runtimeService.setVariables(processInstanceId, taskVariables);
            taskService.complete(task.getId(), taskVariables);

            log.info("[TaskComplete] Task '{}' completed for participant {}", task.getId(), id);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("completed", true);
            payload.put("participantId", id);
            payload.put("taskId", task.getId());

            return ResponseEntity.ok(Response.ok(payload));

        } catch (Exception e) {
            log.error("[TaskComplete] Failed to complete task for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to complete task: " + e.getMessage()));
        }
    }

    @PutMapping("/participants/{id}/position")
    public ResponseEntity<Response<String>> setParticipantPosition(@PathVariable String id,
                                                                   @RequestBody Map<String, String> requestBody) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }
            String newPosition = requestBody.get("position");
            if (newPosition == null || newPosition.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Position cannot be empty"));
            }
            participantDataService.updateParticipantPosition(id, newPosition);
            synchronizeParticipantTour(id);

            // Automatically update the positions of any bound partners to ensure consistency in the environment
            List<String> boundPartners = boundParticipantsMonitor.getBoundPartners(id);
            for (String partnerId : boundPartners) {
                participantDataService.updateParticipantPosition(partnerId, newPosition);
                synchronizeParticipantTour(partnerId);
                log.info("[Environment API] Auto-updated bound partner '{}' position to: {}", partnerId, newPosition);
            }

            log.info("[Environment API] Participant '{}' position set to: {}", id, newPosition);
            return ResponseEntity.ok(Response.ok(newPosition));
        } catch (Exception e) {
            log.error("[Environment API] Failed to set position for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to set position: " + e.getMessage()));
        }
    }

    /**
     * Updates a participant's position by resolving GPS coordinates to a physical place.
     * Body: {"latitude": 43.1376, "longitude": 13.0746}
     */
    @PutMapping("/participants/{id}/position/coordinates")
    public ResponseEntity<Response<String>> setParticipantPositionByCoordinates(
            @PathVariable String id,
            @RequestBody Map<String, Double> requestBody) {
        try {
            Optional<Participant> participant = participantDataService.getParticipant(id);
            if (participant.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }

            Double latitude = requestBody.get("latitude");
            Double longitude = requestBody.get("longitude");
            if (latitude == null || longitude == null) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Request body must contain 'latitude' and 'longitude'"));
            }

            Optional<PhysicalPlace> resolvedPlace = environmentDataService.resolvePhysicalPlaceByCoordinates(latitude, longitude);
            String placeId = resolvedPlace.map(PhysicalPlace::getId).orElse(null);

            participantDataService.updateParticipantPosition(id, placeId);
            synchronizeParticipantTour(id);

            // Automatically update the positions of any bound partners to ensure consistency in the environment
            List<String> boundPartners = boundParticipantsMonitor.getBoundPartners(id);
            for (String partnerId : boundPartners) {
                participantDataService.updateParticipantPosition(partnerId, placeId);
                synchronizeParticipantTour(partnerId);
                log.info("[Environment API] Auto-updated bound partner '{}' position via coordinates to: {}", partnerId, placeId);
            }

            return ResponseEntity.ok(Response.ok(placeId));
        } catch (Exception e) {
            log.error("[Environment API] Failed to resolve coordinates for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to resolve coordinates: " + e.getMessage()));
        }
    }

    /**
     * Updates a participant's attributes and synchronizes them with the Camunda process variables.
     *
     * @param id            The ID of the participant whose attributes to update.
     * @param newAttributes A map containing the new attribute values.
     * @return A ResponseEntity containing the updated participant or an error message.
     */
    @PutMapping("/participants/{id}/attributes")
    public ResponseEntity<Response<Participant>> updateParticipantAttributes(
            @PathVariable String id,
            @RequestBody Map<String, Object> newAttributes) {

        try {
            Optional<Participant> participantOpt = participantDataService.getParticipant(id);
            if (participantOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Participant not found: " + id));
            }

            if (newAttributes == null || newAttributes.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Request body must not be empty"));
            }

            // 1. Update in the local data service
            participantDataService.updateParticipantAttributes(id, newAttributes);

            Participant updatedParticipant = participantDataService.getParticipant(id)
                    .orElse(participantOpt.get());

            // 2. Synchronize attributes within the Camunda ProcessInstance (flatten and update "attributes" map)
            Task activeTask = findActiveUserTaskForParticipant(id);
            if (activeTask != null) {
                String processInstanceId = activeTask.getProcessInstanceId();
                runtimeService.setVariable(processInstanceId, "attributes", updatedParticipant.getAttributes());
                runtimeService.setVariables(processInstanceId, updatedParticipant.getAttributes());
                log.info("[Environment API] Synchronized updated attributes for participant '{}' to Camunda ProcessInstance {}",
                        id, processInstanceId);
            }

            return ResponseEntity.ok(Response.ok(updatedParticipant));
        } catch (Exception e) {
            log.error("[Environment API] Failed to update attributes for participant: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to update participant attributes: " + e.getMessage()));
        }
    }

    /**
     * Receives a lumen reading from a light sensor installed in a physical place.
     * If lumen >= threshold (default 300, configurable via sensors.light.threshold-lumen),
     * sets light=on, otherwise light=off.
     * Body: {"lumen": 450.0}
     */
    @PutMapping("/pp/{id}/sensors/light")
    public ResponseEntity<Response<String>> updateLightSensor(@PathVariable String id,
                                                              @RequestBody Map<String, Double> requestBody) {
        try {
            Double lumen = requestBody.get("lumen");
            if (lumen == null) {
                return ResponseEntity.badRequest()
                        .body(Response.error("Request body must contain 'lumen'"));
            }

            Optional<String> lightState = sensorDataService.updateLightSensor(id, lumen);
            if (lightState.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Physical place not found: " + id));
            }

            return ResponseEntity.ok(Response.ok(lightState.get()));
        } catch (Exception e) {
            log.error("[Environment API] Failed to update light sensor for place: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to update light sensor: " + e.getMessage()));
        }
    }

    /**
     * Synchronizes the participant's tour with the Camunda process instance,
     * ensuring that the participant's current position and attributes are reflected in the process variables.
     * @param participantId The ID of the participant whose tour needs to be synchronized.
     */
    private void synchronizeParticipantTour(String participantId) {
        Optional<Participant> participantOpt = participantDataService.getParticipant(participantId);
        if (participantOpt.isEmpty()) {
            return;
        }

        // 1. Identify the active Process Instance associated with the current participant
        Task activeTask = findActiveUserTaskForParticipant(participantId);
        String processInstanceId = activeTask != null ? activeTask.getProcessInstanceId() : null;
        if (processInstanceId == null) {
            processInstanceId = runtimeService.createProcessInstanceQuery()
                    .active()
                    .variableValueEquals("participantId", participantId)
                    .list()
                    .stream()
                    .findFirst()
                    .map(ProcessInstance::getProcessInstanceId)
                    .orElse(null);
        }

        // 2. If a process instance is found, calculate and synchronize the tour for the participant
        if (processInstanceId != null) {
            TourResponse synchronizedTour = participantTourService.calculateAndSyncTour(participantOpt.get(), processInstanceId);
            List<String> orderedTargets = synchronizedTour != null
                    ? synchronizedTour.getTargetPlaceIds()
                    : null;
            if (orderedTargets != null && !orderedTargets.isEmpty()) {
                movementTaskRegistry.updateDestinationFromTour(participantId, orderedTargets.get(0));

                // 3. If the participant is bound to others, update their destinations as well
                List<String> boundPartners = boundParticipantsMonitor.getBoundPartners(participantId);
                for (String partnerId : boundPartners) {
                    movementTaskRegistry.updateDestinationFromTour(partnerId, orderedTargets.get(0));
                }
            }
        }
    }

    /**
     * Private helper method to securely and exhaustively locate the active User Task for a given participant.
     *
     * @param participantId The ID of the participant for whom to find the active User Task.
     * @return The active Task if found, or null if no active task exists for the participant.
     */
    private Task findActiveUserTaskForParticipant(String participantId) {
        // 1. Search by process instance business key
        Task task = taskService.createTaskQuery()
                .processInstanceBusinessKey(participantId)
                .active()
                .singleResult();

        // 2. Fallback: Search by task assignee
        if (task == null) {
            task = taskService.createTaskQuery()
                    .taskAssignee(participantId)
                    .active()
                    .singleResult();
        }

        // 3. Fallback: Search by process variable "participantId"
        if (task == null) {
            List<Task> tasksWithVar = taskService.createTaskQuery()
                    .processVariableValueEquals("participantId", participantId)
                    .active()
                    .list();
            if (!tasksWithVar.isEmpty()) {
                task = tasksWithVar.get(0);
            }
        }

        // 4. Fallback: Resolution via ParticipantService
        if (task == null) {
            List<Task> activeTasks = taskService.createTaskQuery().active().list();
            task = activeTasks.stream()
                    .filter(t -> participantId.equals(participantService.resolveParticipantForTask(t)))
                    .findFirst()
                    .orElse(null);
        }

        return task;
    }

    /**
     * Private helper method to check if a tour is empty, meaning it has no target places and no navigation path.
     *
     * @param tour The tour to check.
     * @return true if the tour is empty, false otherwise.
     */
    private boolean isTourEmpty(TourResponse tour) {
        if (tour == null) return true;
        boolean noTargets = tour.getTargetPlaceIds() == null || tour.getTargetPlaceIds().isEmpty();
        boolean noPath = tour.getNavigationPath() == null || tour.getNavigationPath().isEmpty();
        return noTargets && noPath;
    }
}