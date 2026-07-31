import { EnvironmentData, Place } from '../types/environment';
import { Participant } from '../types/navigation';

export interface TourResult {
    targetPlaceIds: string[];
    navigationPath: string[];
}

export interface TourStep {
    placeId: string;
    order: number;
    label?: string;
}

/**
 * Calculates the target nodes and the optimal accessible route for the participant.
 */
export const generateGenericTour = (
    environment: EnvironmentData,
    participant: Participant
): TourResult => {
    const { places, edges } = environment;
    const currentPlaceId = participant.currentPlaceId;

    const attrs = participant.attributes || {};
    const userPrefs: string[] = Array.isArray(attrs.preferences) ? attrs.preferences : [];
    const needsAccessibility: boolean = attrs.needs_accessibility === true || attrs.needs_accessibility === 'true';

    // 1. Find target places (Points of Interest) matching participant preferences
    const targetPlaces = places.filter((place) => {
        const pAttrs = place.attributes || {};
        const placeTags: string[] = Array.isArray(pAttrs.tags) ? pAttrs.tags :
            Array.isArray(pAttrs.preferences) ? pAttrs.preferences : [];

        return userPrefs.some((pref) => placeTags.includes(pref));
    });

    const targetPlaceIds = targetPlaces.map((p) => p.id);

    if (!currentPlaceId || targetPlaceIds.length === 0) {
        return { targetPlaceIds, navigationPath: currentPlaceId ? [currentPlaceId] : [] };
    }

    // 2. Build adjacency list for graph traversal (filtering out inaccessible places if required)
    const adjList: Record<string, string[]> = {};
    places.forEach((p) => { adjList[p.id] = []; });

    edges.forEach((edge) => {
        const sourcePlace = places.find((p) => p.id === edge.source);
        const targetPlace = places.find((p) => p.id === edge.target);

        if (needsAccessibility) {
            const isSourceInaccessible = sourcePlace?.attributes?.has_stairs === true || sourcePlace?.attributes?.accessible === false;
            const isTargetInaccessible = targetPlace?.attributes?.has_stairs === true || targetPlace?.attributes?.accessible === false;
            if (isSourceInaccessible || isTargetInaccessible) return;
        }

        adjList[edge.source]?.push(edge.target);
        adjList[edge.target]?.push(edge.source);
    });

    // Helper function using BFS to find the shortest path between two nodes
    const findShortestPath = (start: string, goal: string): string[] => {
        if (start === goal) return [start];
        const visited = new Set<string>([start]);
        const queue: string[][] = [[start]];

        while (queue.length > 0) {
            const path = queue.shift()!;
            const node = path[path.length - 1];

            if (node === goal) return path;

            for (const neighbor of adjList[node] || []) {
                if (!visited.has(neighbor)) {
                    visited.add(neighbor);
                    queue.push([...path, neighbor]);
                }
            }
        }
        return []; // Return empty array if unreachable
    };

    // 3. Connect starting location to remaining POIs in sequence (Nearest Neighbor)
    let fullPath: string[] = [currentPlaceId];
    let currNode = currentPlaceId;

    // Filter out current place immediately if it is already in target list
    let remainingTargets = targetPlaceIds.filter((id) => id !== currentPlaceId);

    while (remainingTargets.length > 0) {
        let bestPath: string[] = [];
        let nextTargetIndex = -1;

        remainingTargets.forEach((target, index) => {
            const path = findShortestPath(currNode, target);

            // Valid path found (must contain at least 2 nodes: currNode -> target)
            if (path.length > 1) {
                if (bestPath.length === 0 || path.length < bestPath.length) {
                    bestPath = path;
                    nextTargetIndex = index;
                }
            }
        });

        if (nextTargetIndex !== -1 && bestPath.length > 1) {
            // Append path nodes to main route (excluding starting node to prevent duplication)
            fullPath.push(...bestPath.slice(1));
            currNode = remainingTargets[nextTargetIndex];
            remainingTargets.splice(nextTargetIndex, 1);
        } else {
            // Remaining targets are completely unreachable
            break;
        }
    }

    return {
        targetPlaceIds,
        navigationPath: fullPath,
    };
};

/**
 * Converts calculated tour results into the step format required by the Leaflet Map component.
 */
export const buildTourStepsForMap = (
    tourResult: TourResult,
    places: Place[]
): TourStep[] => {
    return tourResult.navigationPath.map((placeId, index) => {
        const place = places.find((p) => p.id === placeId);
        return {
            placeId,
            order: index + 1,
            label: place?.name || `Step ${index + 1}`,
        };
    });
};