import React, { useMemo } from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import { Place } from '../types/environment';
import { TourResult } from '../services/navigationService';

interface POIListProps {
    tourResult: TourResult;
    places: Place[];
}

export const POIList: React.FC<POIListProps> = ({ tourResult, places }) => {
    // Extract targetPlaceIds and handle fallback properties for calculated path (navigationPath or path)
    const { targetPlaceIds = [], navigationPath } = tourResult || {};
    const currentPath: string[] = navigationPath || (tourResult as any)?.path || [];

// Filter and sort target places based on their order in currentPath
    const remainingTargetPlaces = useMemo(() => {
        if (!currentPath.length) return [];

        // Create a map to quickly look up the first index of each place in the current path
        const pathOrderMap = new Map<string, number>();
        currentPath.forEach((id, index) => {
            if (!pathOrderMap.has(id)) {
                pathOrderMap.set(id, index);
            }
        });

        // Filter places that are both in targetPlaceIds and in currentPath, then sort by path order
        return places
            .filter((p) => targetPlaceIds.includes(p.id) && pathOrderMap.has(p.id))
            .sort((a, b) => (pathOrderMap.get(a.id) ?? Infinity) - (pathOrderMap.get(b.id) ?? Infinity));
    }, [places, targetPlaceIds, currentPath]);

    const targetSet = useMemo(() => new Set(targetPlaceIds), [targetPlaceIds]);
    const visitedTargets = new Set<string>();

    return (
        <View style={styles.container}>
            <Text style={styles.title}>
                📍 Points of Interest Found ({remainingTargetPlaces.length})
            </Text>

            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.badgeContainer}>
                {remainingTargetPlaces.map((place) => (
                    <View key={place.id} style={styles.badge}>
                        <Text style={styles.badgeText}>★ {place.name || place.id}</Text>
                    </View>
                ))}
            </ScrollView>

            {/* 2. FULL PHYSICAL ITINERARY (TURN-BY-TURN) */}
            <Text style={styles.subtitle}>Calculated Path</Text>
            <ScrollView style={{ maxHeight: 220 }}>
                {currentPath.map((placeId, index) => {
                    const place = places.find((p) => p.id === placeId);
                    const isStart = index === 0;

                    const isTarget = targetSet.has(placeId);
                    const isFirstTimeTarget = isTarget && !visitedTargets.has(placeId);

                    if (isTarget) {
                        visitedTargets.add(placeId);
                    }

                    return (
                        <View key={`${placeId}-${index}`} style={styles.stepRow}>
                            <View style={[
                                styles.stepBadge,
                                isFirstTimeTarget && styles.targetBadge,
                                isStart && !isTarget && styles.startBadge
                            ]}>
                                <Text style={styles.stepNumber}>{index + 1}</Text>
                            </View>
                            <View>
                                <Text style={[styles.placeName, isFirstTimeTarget && styles.targetPlaceName]}>
                                    {place?.name || placeId}
                                </Text>
                                <Text style={styles.roleText}>
                                    {isFirstTimeTarget
                                        ? (isStart ? 'Start & POI Destination' : 'POI Destination')
                                        : isStart
                                            ? 'Start / Origin'
                                            : 'Transit / Waypoint'}
                                </Text>
                            </View>
                        </View>
                    );
                })}
            </ScrollView>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        backgroundColor: '#ffffff',
        padding: 16,
        borderRadius: 12,
        marginTop: 12,
        borderWidth: 1,
        borderColor: '#cbd5e1',
    },
    title: { fontSize: 16, fontWeight: '700', color: '#0f172a', marginBottom: 8 },
    subtitle: { fontSize: 13, fontWeight: '600', color: '#64748b', marginTop: 12, marginBottom: 8, textTransform: 'uppercase' },
    badgeContainer: { flexDirection: 'row', marginBottom: 4 },
    badge: { backgroundColor: '#eff6ff', borderColor: '#2563eb', borderWidth: 1, paddingVertical: 4, paddingHorizontal: 10, borderRadius: 16, marginRight: 8 },
    badgeText: { color: '#2563eb', fontWeight: '600', fontSize: 13 },
    stepRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: '#f1f5f9' },
    stepBadge: { width: 24, height: 24, borderRadius: 12, backgroundColor: '#94a3b8', alignItems: 'center', justifyContent: 'center', marginRight: 10 },
    startBadge: { backgroundColor: '#0284c7' },
    targetBadge: { backgroundColor: '#2563eb' },
    stepNumber: { color: '#ffffff', fontSize: 11, fontWeight: '700' },
    placeName: { fontSize: 14, fontWeight: '500', color: '#334155' },
    targetPlaceName: { fontWeight: '700', color: '#1e40af' },
    roleText: { fontSize: 11, color: '#94a3b8' },
});