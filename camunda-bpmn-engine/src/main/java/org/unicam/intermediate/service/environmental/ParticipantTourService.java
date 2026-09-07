package org.unicam.intermediate.service.environmental;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Execution;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.dto.TourResponse;
import org.unicam.intermediate.models.pojo.Edge;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.service.ProcessVariableService;
import org.unicam.intermediate.service.environmental.movement.MovementTaskRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service responsible for calculating optimal spatial navigation tours and target physical places
 * for process participants based on preferences and accessibility constraints.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantTourService {

    private final EnvironmentDataService environmentDataService;
    private final ProcessVariableService processVariableService;
    private final MovementTaskRegistry movementTaskRegistry;
    private final RuntimeService runtimeService;

    /**
     * In-memory registry to track visited physical places per participant.
     * Prevents previously visited POIs from reappearing in calculated navigation paths.
     */
    private final Map<String, Set<String>> visitedPlacesMap = new ConcurrentHashMap<>();

    /**
     * Internal record to hold both the full navigation path and the dynamically ordered target POI IDs.
     */
    private record TourPathResult(List<String> orderedTargetIds, List<String> navigationPath) {
    }

    /**
     * Calculates and synchronizes recalculated target places directly into Camunda process variables
     * and updates the active movement pending action in MovementTaskRegistry.
     *
     * @param participant       the participant entity
     * @param processInstanceId active process instance ID
     * @return recalculated {@link TourResponse}
     */
    public TourResponse calculateAndSyncTour(Participant participant, String processInstanceId) {
        if (participant.getPosition() == null || participant.getPosition().isBlank()) {
            return null;
        }

        TourResponse response = calculateTour(participant);
        if (processInstanceId == null || response == null || response.getTargetPlaceIds() == null) {
            return response;
        }

        List<String> orderedTargets = new ArrayList<>(response.getTargetPlaceIds());

        if (!orderedTargets.isEmpty()) {
            String nextTarget = orderedTargets.get(0);

            List<Execution> activeExecutions = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .list();

            for (Execution exec : activeExecutions) {
                List<String> activeActivityIds = runtimeService.getActiveActivityIds(exec.getId());
                if (!activeActivityIds.isEmpty()) {
                    String activityId = activeActivityIds.get(0);

                    if (!movementTaskRegistry.hasActiveMovementTask(participant.getId())) {
                        movementTaskRegistry.registerTask(
                                participant.getId(),
                                exec.getId(),
                                activityId,
                                nextTarget,
                                null
                        );
                    }
                    break;
                }
            }
        }

        try {
            if (processVariableService.variableExists(processInstanceId, "Tour_settings")) {
                processVariableService.setNestedField(processInstanceId, "Tour_settings", "targetPlaceIds", orderedTargets);
            } else {
                processVariableService.setVariable(processInstanceId, "targetPlaceIds", orderedTargets);
            }
            log.info("[TOUR_SERVICE] Synchronized ordered targetPlaceIds to process instance {}: {}",
                    processInstanceId, orderedTargets);
        } catch (Exception e) {
            log.warn(
                    "[TOUR_SERVICE] Could not synchronize targetPlaceIds for process {}: {}",
                    processInstanceId,
                    e.getMessage()
            );
        }

        return response;
    }

    /**
     * Calculates target places and shortest navigation path dynamically based on participant attributes,
     * accessibility restrictions, and previously visited nodes.
     *
     * @param participant the participant requesting path calculation
     * @return calculated {@link TourResponse}
     */
    public TourResponse calculateTour(Participant participant) {
        if (participant == null) {
            return emptyTour();
        }

        List<PhysicalPlace> places = environmentDataService.getPhysicalPlaces();
        List<Edge> edges = environmentDataService.getEdges();

        // Safely retrieve participant identifier and position
        String currentPlaceId = participant.getPosition();
        String participantId = participant.getId();

        if (places == null || places.isEmpty()) {
            places = List.of();
        }

        if (edges == null || edges.isEmpty()) {
            edges = List.of();
        }

        // Register current position as visited if both ID and position are present
        if (participantId != null && !participantId.isBlank() && currentPlaceId != null && !currentPlaceId.isBlank()) {
            visitedPlacesMap
                    .computeIfAbsent(participantId, k -> new HashSet<>())
                    .add(currentPlaceId);
        }

        Set<String> visitedPlaces = (participantId != null)
                ? visitedPlacesMap.getOrDefault(participantId, Set.of())
                : Set.of();

        Map<String, Object> participantAttrs = (participant.getAttributes() != null)
                ? participant.getAttributes()
                : Map.of();

        // Flexible preferences extraction across multiple fallback keys
        List<String> participantPreferences = extractStringList(
                participantAttrs,
                "choices", "preferences", "interests", "tags", "attributes", "categories"
        );

        boolean needsAccessibility = isAccessibilityRequired(participantAttrs);

        List<PhysicalPlace> targetPlaces = places.stream()
                .filter(place -> !isInaccessible(place))
                .filter(place -> matchesParticipantPreferences(place, participantPreferences))
                .toList();

        List<String> targetPlaceIds = targetPlaces.stream()
                .map(PhysicalPlace::getId)
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> !visitedPlaces.contains(id))
                .collect(Collectors.toList());

        // If initial position is missing, return sorted target place IDs with an empty navigation path
        if (currentPlaceId == null || currentPlaceId.isBlank()) {
            targetPlaceIds.sort(String::compareTo);
            return new TourResponse(targetPlaceIds, List.of());
        }

        if (targetPlaceIds.isEmpty()) {
            return emptyTour();
        }

        // Build the environment graph
        Map<String, Set<String>> adjacency = buildAdjacency(places, edges, needsAccessibility);

        // Calculate the navigation path and dynamically order the target places according to the shortest reachable path.
        TourPathResult tourPathResult = buildNavigationPathAndOrder(currentPlaceId, targetPlaceIds, adjacency);

        return new TourResponse(tourPathResult.orderedTargetIds(), tourPathResult.navigationPath());
    }

    /**
     * Recalculates the route when a location is closed or changed in the environment.
     *
     * @param participant   the participant to re-route
     * @param closedPlaceId the ID of the location that became inaccessible
     * @return new {@link TourResponse} excluding the blocked node from the graph and destinations
     */
    public TourResponse handleEnvironmentalChange(Participant participant, String closedPlaceId) {
        log.info("[ParticipantTourService] Recalculating route for participant {} due to closure of location: {}",
                participant != null ? participant.getId() : "unknown", closedPlaceId);

        return calculateTour(participant);
    }

    private boolean matchesParticipantPreferences(PhysicalPlace place, List<String> participantPreferences) {
        if (place == null || participantPreferences.isEmpty()) {
            return false;
        }

        List<String> placeTags = extractStringList(place.getAttributes(), "tags", "preferences", "choices", "type", "category");
        if (placeTags.isEmpty()) {
            return false;
        }

        Set<String> normalizedPlaceTags = placeTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> requiredPreferences = participantPreferences.stream()
                .filter(pref -> pref != null && !pref.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requiredPreferences.isEmpty()) return false;

        return normalizedPlaceTags.containsAll(requiredPreferences);
    }

    /**
     * Builds undirected graph adjacency list considering accessibility barriers if required.
     *
     * @param places             list of available physical places
     * @param edges              list of environmental edges/connections
     * @param needsAccessibility flag indicating whether accessible paths are strictly required
     * @return graph representation as an adjacency map
     */
    private Map<String, Set<String>> buildAdjacency(List<PhysicalPlace> places, List<Edge> edges, boolean needsAccessibility) {
        if (places == null) places = List.of();
        if (edges == null) edges = List.of();

        List<PhysicalPlace> finalPlaces = places;
        Set<String> physicalPlaceIds = places.stream()
                .filter(Objects::nonNull)
                .map(PhysicalPlace::getId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .filter(id -> finalPlaces.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(place -> id.equals(place.getId()) &&
                                !isInaccessible(place) &&
                                (!needsAccessibility || isAccessible(place))))
                .collect(Collectors.toSet());

        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
                continue;
            }

            String from = edge.getSource();
            String to = edge.getTarget();
            if (!physicalPlaceIds.contains(from) || !physicalPlaceIds.contains(to)) {
                continue;
            }

            adjacency.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
            adjacency.computeIfAbsent(to, ignored -> new HashSet<>()).add(from);
        }

        return adjacency;
    }

    private boolean isAccessible(PhysicalPlace place) {
        if (place == null || place.getAttributes() == null) {
            return true;
        }

        Object accessible = place.getAttributes().get("accessible");
        Object isAccessible = place.getAttributes().get("is_accessible");

        if (Boolean.FALSE.equals(accessible)) {
            return false;
        }

        if (Boolean.FALSE.equals(isAccessible)) {
            return false;
        }

        return true;
    }

    private boolean isInaccessible(PhysicalPlace place) {
        if (place == null || place.getAttributes() == null) {
            return false;
        }

        Map<String, Object> attributes = place.getAttributes();
        Object hasStairs = attributes.get("has_stairs");
        Object accessible = attributes.get("accessible");
        Object isAccessible = attributes.get("is_accessible");
        Object state = attributes.get("state");
        Object status = attributes.get("status");

        if (Boolean.TRUE.equals(hasStairs) || Boolean.FALSE.equals(accessible)) {
            return true;
        }

        // Monitoring of temporary environmental conditions (e.g., cleaning/maintenance)
        if (Boolean.FALSE.equals(isAccessible)) {
            return true;
        }

        // Check for closed status/state (case-insensitive)
        if ((status instanceof String statusStr && "CLOSED".equalsIgnoreCase(statusStr.trim())) ||
                (state instanceof String stateStr && "CLOSED".equalsIgnoreCase(stateStr.trim()))) {
            return true;
        }

        return false;
    }

    private boolean isAccessibilityRequired(Map<String, Object> attributes) {
        if (attributes == null) return false;

        for (String key : new String[]{"needs_accessibility", "accessible", "wheelchair"}) {
            Object value = attributes.get(key);
            if (value instanceof Boolean bool && bool) {
                return true;
            }
            if (value instanceof String str && Boolean.parseBoolean(str)) {
                return true;
            }
        }

        return false;
    }

    private TourPathResult buildNavigationPathAndOrder(String currentPlaceId, List<String> targetPlaceIds, Map<String, Set<String>> adjacency) {
        List<String> fullPath = new ArrayList<>();
        List<String> orderedTargets = new ArrayList<>();

        fullPath.add(currentPlaceId);

        String currentNode = currentPlaceId;
        List<String> remainingTargets = new ArrayList<>(targetPlaceIds.stream()
                .filter(id -> !currentPlaceId.equals(id))
                .toList());

        while (!remainingTargets.isEmpty()) {
            List<String> bestPath = List.of();
            int nextTargetIndex = -1;

            for (int index = 0; index < remainingTargets.size(); index++) {
                String target = remainingTargets.get(index);
                List<String> path = findShortestPath(currentNode, target, adjacency);
                if (path.size() > 1 && (bestPath.isEmpty() || path.size() < bestPath.size())) {
                    bestPath = path;
                    nextTargetIndex = index;
                }
            }

            if (nextTargetIndex == -1 || bestPath.size() <= 1) {
                break;
            }

            fullPath.addAll(bestPath.subList(1, bestPath.size()));
            String visitedTarget = remainingTargets.get(nextTargetIndex);
            orderedTargets.add(visitedTarget);

            currentNode = visitedTarget;
            remainingTargets.remove(nextTargetIndex);
        }

        return new TourPathResult(orderedTargets, fullPath);
    }

    /**
     * Breadth-First Search (BFS) for finding the shortest path between two nodes in the environment graph.
     *
     * @param start     starting node ID
     * @param goal      target node ID
     * @param adjacency graph adjacency map
     * @return path of node IDs from start to goal
     */
    private List<String> findShortestPath(String start, String goal, Map<String, Set<String>> adjacency) {
        if (start.equals(goal)) {
            return List.of(start);
        }

        Set<String> visited = new HashSet<>();
        ArrayDeque<List<String>> queue = new ArrayDeque<>();
        queue.add(List.of(start));
        visited.add(start);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            if (path == null || path.isEmpty()) {
                continue;
            }

            String node = path.get(path.size() - 1);
            if (goal.equals(node)) {
                return path;
            }

            for (String neighbor : adjacency.getOrDefault(node, Set.of())) {
                if (visited.add(neighbor)) {
                    List<String> nextPath = new ArrayList<>(path);
                    nextPath.add(neighbor);
                    queue.add(nextPath);
                }
            }
        }

        return List.of();
    }

    /**
     * Helper to extract string list values from map attributes using prioritized candidate keys.
     *
     * @param attributes attribute map
     * @param keys       candidate key names in order of evaluation
     * @return extracted list of strings
     */
    private List<String> extractStringList(Map<String, Object> attributes, String... keys) {
        if (attributes == null || keys == null) {
            return List.of();
        }

        for (String key : keys) {
            Object rawValue = attributes.get(key);
            List<String> values = toStringList(rawValue);
            if (!values.isEmpty()) {
                return values;
            }
        }

        return List.of();
    }

    /**
     * Coerces arbitrary object representations into a clean String list.
     *
     * @param rawValue raw object to transform
     * @return sanitized list of string items
     */
    private List<String> toStringList(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }

        if (rawValue instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(s -> s.replaceAll("[\\[\\]\"']", "").trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (rawValue instanceof Map<?, ?> map) {
            return map.keySet().stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(s -> s.replaceAll("[\\[\\]\"']", "").trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (rawValue instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object value : iterable) {
                if (value == null) continue;

                String str = value.toString().replaceAll("[\\[\\]\"']", "").trim();
                if (!str.isBlank()) {
                    values.add(str);
                }
            }
            return values;
        }

        if (rawValue instanceof String str) {
            String trimmed = str.replaceAll("[\\[\\]\"']", "").trim();
            if (trimmed.isBlank()) {
                return List.of();
            }

            if (trimmed.contains(",")) {
                return Stream.of(trimmed.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
            }

            return List.of(trimmed);
        }

        return List.of();
    }

    private TourResponse emptyTour() {
        return new TourResponse(
                List.of(),
                List.of()
        );
    }
}