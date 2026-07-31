package org.unicam.intermediate.service.environmental;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.unicam.intermediate.models.dto.TourResponse;
import org.unicam.intermediate.models.pojo.Edge;
import org.unicam.intermediate.models.pojo.Participant;
import org.unicam.intermediate.models.pojo.PhysicalPlace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParticipantTourService {

    private final EnvironmentDataService environmentDataService;

    public TourResponse calculateTour(Participant participant) {
        List<PhysicalPlace> places = environmentDataService.getPhysicalPlaces();
        List<Edge> edges = environmentDataService.getEdges();
        String currentPlaceId = participant != null ? participant.getPosition() : null;

        List<String> participantPreferences = extractStringList(
                participant != null ? participant.getAttributes() : null,
                "choices",
                "preferences"
        );

        boolean needsAccessibility = isTruthy(
                participant != null ? participant.getAttributes() : null,
                "needs_accessibility"
        );

        List<PhysicalPlace> targetPlaces = places.stream()
                .filter(place -> matchesParticipantPreferences(place, participantPreferences))
                .toList();

        List<String> targetPlaceIds = targetPlaces.stream()
                .map(PhysicalPlace::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        if (currentPlaceId == null || currentPlaceId.isBlank() || targetPlaceIds.isEmpty()) {
            return new TourResponse(targetPlaceIds, currentPlaceId != null && !currentPlaceId.isBlank()
                    ? List.of(currentPlaceId)
                    : List.of());
        }

        Map<String, Set<String>> adjacency = buildAdjacency(places, edges, needsAccessibility);
        List<String> navigationPath = buildNavigationPath(currentPlaceId, targetPlaceIds, adjacency);

        return new TourResponse(targetPlaceIds, navigationPath);
    }

    private boolean matchesParticipantPreferences(PhysicalPlace place, List<String> participantPreferences) {
        if (place == null || participantPreferences.isEmpty()) {
            return false;
        }

        List<String> placeTags = extractStringList(place.getAttributes(), "tags", "preferences");
        if (placeTags.isEmpty()) {
            return false;
        }

        Set<String> preferences = participantPreferences.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String tag : placeTags) {
            if (preferences.contains(tag)) {
                return true;
            }
        }
        return false;
    }

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

            if (needsAccessibility) {
                Optional<PhysicalPlace> sourcePlace = places.stream().filter(p -> from.equals(p.getId())).findFirst();
                Optional<PhysicalPlace> targetPlace = places.stream().filter(p -> to.equals(p.getId())).findFirst();
                if (isInaccessible(sourcePlace.orElse(null)) || isInaccessible(targetPlace.orElse(null))) {
                    continue;
                }
            }

            adjacency.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
            adjacency.computeIfAbsent(to, ignored -> new HashSet<>()).add(from);
        }

        return adjacency;
    }

    private boolean isInaccessible(PhysicalPlace place) {
        if (place == null || place.getAttributes() == null) {
            return false;
        }

        Object hasStairs = place.getAttributes().get("has_stairs");
        Object accessible = place.getAttributes().get("accessible");

        return Boolean.TRUE.equals(hasStairs) || Boolean.FALSE.equals(accessible);
    }

    private List<String> buildNavigationPath(String currentPlaceId, List<String> targetPlaceIds, Map<String, Set<String>> adjacency) {
        List<String> fullPath = new ArrayList<>();
        fullPath.add(currentPlaceId);

        String currentNode = currentPlaceId;
        List<String> remainingTargets = new ArrayList<>();
        for (String id : targetPlaceIds) {
            if (!currentPlaceId.equals(id)) {
                remainingTargets.add(id);
            }
        }

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
                currentNode = remainingTargets.get(nextTargetIndex);
                remainingTargets.remove(nextTargetIndex);
            } else {
                break;
            }
        }

        return fullPath;
    }

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

    private List<String> toStringList(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }

        if (rawValue instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (rawValue instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object value : iterable) {
                if (value instanceof String str && !str.trim().isBlank()) {
                    values.add(str.trim());
                }
            }
            return values;
        }

        if (rawValue instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isBlank()) {
                return List.of();
            }

            if (trimmed.contains(",")) {
                return List.of(trimmed.split(",")).stream()
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
            }

            return List.of(trimmed);
        }

        return List.of();
    }

    private boolean isTruthy(Map<String, Object> attributes, String key) {
        if (attributes == null || key == null) {
            return false;
        }

        Object value = attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }

        return value instanceof String str && Boolean.parseBoolean(str);
    }
}
