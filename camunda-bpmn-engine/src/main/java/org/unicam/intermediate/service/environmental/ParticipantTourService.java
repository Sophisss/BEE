package org.unicam.intermediate.service.environmental;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.dto.TourResponse;
import org.unicam.intermediate.models.pojo.Edge;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.models.pojo.PhysicalPlace;
import org.unicam.intermediate.service.ProcessVariableService;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service responsible for calculating optimal spatial navigation tours and target physical places
 * for process participants based on preferences, accessibility constraints, visited history, and historical cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipantTourService {

    private final EnvironmentDataService environmentDataService;
    private final RuntimeService runtimeService;
    private final ProcessVariableService processVariableService;

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
     * Resolves the navigation tour for a given participant.
     *
     * @param participant the target participant entity
     * @return {@link TourResponse} containing target place IDs and calculated navigation path
     */
    public TourResponse resolveTourForParticipant(Participant participant) {
        return calculateTour(participant);
    }

    /**
     * Calculates and synchronizes recalculated target places directly into Camunda process variables.
     *
     * @param participant       the participant entity
     * @param processInstanceId active process instance ID
     * @return recalculated {@link TourResponse}
     */
    public TourResponse calculateAndSyncTour(Participant participant, String processInstanceId) {
        TourResponse response = calculateTour(participant);

        if (processInstanceId != null && response != null && response.getTargetPlaceIds() != null && !response.getTargetPlaceIds().isEmpty()) {
            try {
                if (processVariableService.variableExists(processInstanceId, "Tour_settings")) {
                    processVariableService.setNestedField(processInstanceId, "Tour_settings", "targetPlaceIds", response.getTargetPlaceIds());
                } else {
                    processVariableService.setVariable(processInstanceId, "targetPlaceIds", response.getTargetPlaceIds());
                }
                log.info("[TOUR_SERVICE] Synchronized ordered targetPlaceIds to process instance {}: {}",
                        processInstanceId, response.getTargetPlaceIds());
            } catch (Exception e) {
                log.warn("[TOUR_SERVICE] Could not sync targetPlaceIds to process instance: {}", e.getMessage());
            }
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
            return new TourResponse(List.of(), List.of());
        }

        List<PhysicalPlace> places = environmentDataService.getPhysicalPlaces();
        List<Edge> edges = environmentDataService.getEdges();

        // Safely retrieve participant identifier and position
        String currentPlaceId = participant.getPosition();
        String participantId = participant.getId();

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
                // Exclude current location and all previously visited locations
                .filter(id -> !id.equals(currentPlaceId))
                .filter(id -> !visitedPlaces.contains(id))
                .collect(Collectors.toList());

        // If initial position is missing, return sorted target place IDs with an empty navigation path
        if (currentPlaceId == null || currentPlaceId.isBlank()) {
            targetPlaceIds.sort(String::compareTo);
            return new TourResponse(targetPlaceIds, List.of());
        }

        if (targetPlaceIds.isEmpty()) {
            return new TourResponse(List.of(), List.of());
        }

        Map<String, Set<String>> adjacency = buildAdjacency(places, edges, needsAccessibility);

        // Calculate both the full path and the target IDs ordered by encounter sequence
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

    /**
     * Attempts to retrieve the latest cached tour stored within active process instances.
     *
     * @param participant target participant entity
     * @return optional containing the latest candidate tour if found
     */
    private Optional<TourResponse> findLatestStoredTour(Participant participant) {
        if (participant == null || participant.getId() == null || participant.getId().isBlank()) {
            return Optional.empty();
        }

        String participantId = participant.getId();
        List<StoredTourCandidate> candidates = new ArrayList<>();
        List<ProcessInstance> activeInstances = runtimeService.createProcessInstanceQuery().active().list();

        for (ProcessInstance instance : activeInstances) {
            Map<String, Object> variables = runtimeService.getVariables(instance.getProcessInstanceId());
            for (Object rawVariable : variables.values()) {
                if (!(rawVariable instanceof Map<?, ?> variableMap)) {
                    continue;
                }
                Optional<StoredTourCandidate> parsed = toStoredTourCandidate(participantId, variableMap);
                parsed.ifPresent(candidates::add);
            }
        }

        return candidates.stream()
                .max(Comparator.comparing(StoredTourCandidate::generatedAt))
                .map(StoredTourCandidate::tourResponse);
    }

    /**
     * Converts raw process instance variable map to a candidate stored tour.
     *
     * @param participantId target participant identifier
     * @param variableMap   map representation of execution variables
     * @return parsed tour candidate wrapped in an Optional
     */
    private Optional<StoredTourCandidate> toStoredTourCandidate(String participantId, Map<?, ?> variableMap) {
        Object rawParticipant = variableMap.get("participantId");
        if (!(rawParticipant instanceof String storedParticipantId) || !participantId.equals(storedParticipantId)) {
            return Optional.empty();
        }

        List<String> targetPlaceIds = toStringList(variableMap.get("targetPlaceIds"));
        List<String> navigationPath = toStringList(variableMap.get("navigationPath"));
        if (targetPlaceIds.isEmpty() && navigationPath.isEmpty()) {
            return Optional.empty();
        }

        Instant generatedAt = Instant.EPOCH;
        Object rawGeneratedAt = variableMap.get("generatedAt");
        if (rawGeneratedAt instanceof String generatedAtText && !generatedAtText.isBlank()) {
            generatedAt = Instant.parse(generatedAtText);
        }

        return Optional.of(new StoredTourCandidate(
                generatedAt,
                new TourResponse(targetPlaceIds, navigationPath)
        ));
    }

    /**
     * Case-insensitive robust matching between place tags and participant preferences.
     *
     * @param place                  target physical place
     * @param participantPreferences list of preferred attributes/tags
     * @return true if place matches at least one preference, false otherwise
     */
    private boolean matchesParticipantPreferences(PhysicalPlace place, List<String> participantPreferences) {
        if (place == null || participantPreferences.isEmpty()) {
            return false;
        }

        List<String> placeTags = extractStringList(place.getAttributes(), "tags", "preferences", "choices", "type", "category");
        if (placeTags.isEmpty()) {
            return false;
        }

        Set<String> preferences = participantPreferences.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String tag : placeTags) {
            if (tag != null && preferences.contains(tag.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
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
        Set<String> physicalPlaceIds = places.stream()
                .map(PhysicalPlace::getId)
                .filter(id -> id != null && !id.isBlank())
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

            PhysicalPlace sourcePlace = places.stream()
                    .filter(p -> from.equals(p.getId()))
                    .findFirst()
                    .orElse(null);

            PhysicalPlace targetPlace = places.stream()
                    .filter(p -> to.equals(p.getId()))
                    .findFirst()
                    .orElse(null);

            if (isInaccessible(sourcePlace) || isInaccessible(targetPlace)) {
                continue;
            }

            adjacency.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
            adjacency.computeIfAbsent(to, ignored -> new HashSet<>()).add(from);
        }

        return adjacency;
    }

    /**
     * Evaluates if a physical place presents physical accessibility obstacles or is closed.
     *
     * @param place target physical place entity
     * @return true if the location is inaccessible, false otherwise
     */
    private boolean isInaccessible(PhysicalPlace place) {
        if (place == null || place.getAttributes() == null) {
            return false;
        }

        Object hasStairs = place.getAttributes().get("has_stairs");
        Object accessible = place.getAttributes().get("accessible");
        Object isAccessible = place.getAttributes().get("is_accessible");
        Object state = place.getAttributes().get("state");
        Object status = place.getAttributes().get("status");

        // 1. Check for architectural barriers
        if (Boolean.TRUE.equals(hasStairs) || Boolean.FALSE.equals(accessible)) {
            return true;
        }

        // 2. Monitoring of temporary environmental conditions (e.g., cleaning/maintenance)
        if (Boolean.FALSE.equals(isAccessible)) {
            return true;
        }

        // 3. Check for closed status/state (case-insensitive)
        if ((status instanceof String statusStr && "CLOSED".equalsIgnoreCase(statusStr.trim())) ||
                (state instanceof String stateStr && "CLOSED".equalsIgnoreCase(stateStr.trim()))) {
            return true;
        }

        return false;
    }

    /**
     * Calculates sequential path visiting all target places starting from current position using BFS
     * and returns both the full physical navigation path and the target IDs ordered by visit sequence.
     *
     * @param currentPlaceId origin physical place identifier
     * @param targetPlaceIds target destination identifiers
     * @param adjacency      graph adjacency map
     * @return {@link TourPathResult} containing ordered target IDs and full navigation path
     */
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

            if (nextTargetIndex != -1 && bestPath.size() > 1) {
                fullPath.addAll(bestPath.subList(1, bestPath.size()));

                String visitedTarget = remainingTargets.get(nextTargetIndex);
                orderedTargets.add(visitedTarget);

                currentNode = visitedTarget;
                remainingTargets.remove(nextTargetIndex);
            } else {
                break;
            }
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
                    .map(Object::toString)
                    .map(s -> s.replaceAll("[\\[\\]\"']", "").trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (rawValue instanceof Map<?, ?> map) {
            return map.keySet().stream()
                    .map(Object::toString)
                    .map(s -> s.replaceAll("[\\[\\]\"']", "").trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (rawValue instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object value : iterable) {
                if (value != null) {
                    String str = value.toString().replaceAll("[\\[\\]\"']", "").trim();
                    if (!str.isBlank()) {
                        values.add(str);
                    }
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

    /**
     * Determines whether accessibility constraints are requested by the participant.
     *
     * @param attributes participant attribute map
     * @return true if accessibility features are explicitly required
     */
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

    private record StoredTourCandidate(Instant generatedAt, TourResponse tourResponse) {
    }
}