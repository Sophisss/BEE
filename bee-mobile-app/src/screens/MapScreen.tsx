import React, { useState, useEffect, useMemo } from 'react';
import { View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, Platform, Alert } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList, Participant, PendingAction } from '../types/navigation';
import { PlaceAttributesCard } from '../components/PlaceAttributesCard';
import { LeafletMapView } from '../components/LeafletMapView';
import { LogicalPlacesPanel } from '../components/LogicalPlacesPanel';
import { PendingActionsCard } from '../components/PendingActionsCard';
import { POIList } from '../components/POIList';
import { EnvironmentData, Place } from '../types/environment';
import {
    fetchPhysicalPlacesApi,
    fetchLogicalPlacesApi,
    fetchEdgesApi,
    refreshParticipantsApi,
    refreshPendingActionsApi,
    updateParticipantPositionApi,
    updatePlaceAttributeApi,
} from '../services/api';
import {
    generateGenericTour,
    buildTourStepsForMap,
    TourResult,
} from '../services/navigationService';
import { styles } from './MapScreen.styles';

type Props = NativeStackScreenProps<RootStackParamList, 'Map'>;

export const MapScreen: React.FC<Props> = ({route}) => {
    const [participants, setParticipants] = useState<Participant[]>(route.params?.participants || []);
    const [activeParticipantId, setActiveParticipantId] = useState<string>(participants[0]?.id || '');
    const [environment, setEnvironment] = useState<EnvironmentData>({places: [], edges: []});
    const [manualInput, setManualInput] = useState('');
    const [logicalPlaces, setLogicalPlaces] = useState<Record<string, string[]>>({});
    const [pendingActions, setPendingActions] = useState<PendingAction[]>([]);
    const [loadingActions, setLoadingActions] = useState<boolean>(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const [isUpdatingPosition, setIsUpdatingPosition] = useState<boolean>(false);
    const [draftAttributes, setDraftAttributes] = useState<Record<string, any>>({});
    const [isSavingAttributes, setIsSavingAttributes] = useState<boolean>(false);
    const [activeTour, setActiveTour] = useState<TourResult | null>(null);

    const activeParticipant = participants.find((p) => p.id === activeParticipantId);
    const currentPlace = environment.places.find((p) => p.id === activeParticipant?.currentPlaceId);

    // 1. Calculate Tour for the active participant
    useEffect(() => {
        if (environment.places.length > 0 && activeParticipant) {
            const tour = generateGenericTour(environment, activeParticipant);
            setActiveTour(tour);
        }
    }, [environment, activeParticipant, activeParticipantId]);

    // 2. Prepare tour steps for LeafletMapView
    const activeTourSteps = useMemo(() => {
        if (!activeTour) return [];
        return buildTourStepsForMap(activeTour, environment.places);
    }, [activeTour, environment.places]);

    useEffect(() => {
        setDraftAttributes(currentPlace ? (currentPlace.attributes || {}) : {});
    }, [currentPlace?.id]);

    useEffect(() => {
        const fetchEnvironmentData = async () => {
            try {
                const [ppData, edgesData, rawLpData, participantsList] = await Promise.all([
                    fetchPhysicalPlacesApi(),
                    fetchEdgesApi(),
                    fetchLogicalPlacesApi(),
                    refreshParticipantsApi(),
                ]);

                setEnvironment((prev) => ({
                    ...prev,
                    places: ppData.length > 0 ? ppData : prev.places,
                    edges: edgesData.length > 0 ? edgesData : prev.edges,
                }));

                if (participantsList && participantsList.length > 0) setParticipants(participantsList);

                if (rawLpData && rawLpData.length > 0) {
                    const normalizedLp: Record<string, string[]> = {};
                    rawLpData.forEach((logicalGroup: any) => {
                        const groupName = logicalGroup.name || 'Uncategorized';
                        const conditions = logicalGroup.conditions || [];
                        normalizedLp[groupName] = ppData
                            .filter((place: any) => {
                                const attrs = place.attributes || {};
                                return conditions.every((cond: any) => {
                                    const { attribute, operator, value } = cond;
                                    const actualVal = attrs[attribute];
                                    switch (operator) {
                                        case '==':
                                            return actualVal === value;
                                        case '!=':
                                            return actualVal !== value;
                                        case '>':
                                            return actualVal > value;
                                        case '<':
                                            return actualVal < value;
                                        default:
                                            return false;
                                    }
                                });
                            })
                            .map((place: any) => place.id || place.name);
                    });
                    setLogicalPlaces(normalizedLp);
                }
            } catch (err) {
                console.warn('Backend fetch error:', err);
            }
        };

        fetchEnvironmentData().then(_ => console.log('Environment data fetched')).catch(e => console.warn('Error fetching environment data:', e));
        const interval = setInterval(fetchEnvironmentData, 3000);
        return () => clearInterval(interval);
    }, []);

    useEffect(() => {
        if (!activeParticipantId) return;
        const fetchPendingActions = async () => {
            setLoadingActions(true);
            setActionError(null);
            try {
                const actions = await refreshPendingActionsApi(activeParticipantId);
                setPendingActions(actions);
            } catch (error) {
                setActionError('Unable to retrieve pending actions');
                setPendingActions([]);
            } finally {
                setLoadingActions(false);
            }
        };
        fetchPendingActions().then(_ => console.log('Pending actions refreshed')).catch(e => console.warn('Error refreshing pending actions:', e));
    }, [activeParticipantId, activeParticipant?.currentPlaceId]);

    const moveActiveParticipant = async (targetPlaceId: string) => {
        if (!activeParticipantId) return;
        setParticipants((prev) =>
            prev.map((p) => (p.id === activeParticipantId ? { ...p, currentPlaceId: targetPlaceId } : p))
        );
        setIsUpdatingPosition(true);
        try {
            await updateParticipantPositionApi(activeParticipantId, targetPlaceId);
        } catch (error) {
            Alert.alert('Sync Error', 'Position updated locally, but failed on backend.');
        } finally {
            setIsUpdatingPosition(false);
        }
    };

    const handleManualMove = async () => {
        const trimmed = manualInput.trim().toLowerCase();
        if (!trimmed) return;
        const found = environment.places.find(
            (p) => p.id.toLowerCase() === trimmed || (p.name && p.name.toLowerCase().includes(trimmed))
        );
        if (found) {
            await moveActiveParticipant(found.id);
            setManualInput('');
        } else {
            Alert.alert('Place Not Found', `No matching place found for "${manualInput}".`);
        }
    };

    const handleSaveAllAttributes = async () => {
        if (!currentPlace) return;
        setIsSavingAttributes(true);
        const previousAttributes = { ...(currentPlace.attributes || {}) };
        try {
            const parsedAttributes: Record<string, any> = {};
            for (const [key, rawValue] of Object.entries(draftAttributes)) {
                await updatePlaceAttributeApi(currentPlace.id, key, rawValue);
                let parsedValue = rawValue;
                if (rawValue === 'true') parsedValue = true;
                else if (rawValue === 'false') parsedValue = false;
                else if (typeof rawValue === 'string' && !isNaN(Number(rawValue)) && rawValue.trim() !== '') {
                    parsedValue = Number(rawValue);
                }
                parsedAttributes[key] = parsedValue;
            }
            setEnvironment((prev) => ({
                ...prev,
                places: prev.places.map((p) => (p.id === currentPlace.id ? { ...p, attributes: parsedAttributes } : p)),
            }));
            Alert.alert('Success', 'Attributes updated successfully!');
        } catch (error) {
            setDraftAttributes(previousAttributes);
            Alert.alert('Update Failed', 'Reverted attribute changes.');
        } finally {
            setIsSavingAttributes(false);
        }
    };

    const adjacentPlaces = useMemo(() => {
        if (!currentPlace) return [];
        const found = environment.edges
            .filter((e) => e.source === currentPlace.id || e.target === currentPlace.id)
            .map((e) => environment.places.find((p) => p.id === (e.source === currentPlace.id ? e.target : e.source)))
            .filter((p): p is Place => p !== undefined);
        return Array.from(new Map(found.map((p) => [p.id, p])).values());
    }, [environment, currentPlace]);

    return (
        <View style={styles.container}>
            <ScrollView contentContainerStyle={styles.scrollContent}>
                <Text style={styles.headerTitle}>Interactive Map</Text>
                <Text style={styles.headerSubtitle}>
                    Simulate participant movements and manage local place attributes in real time.
                </Text>

                {/* MAIN GRID: LEAFLET MAP + PANELS */}
                <View style={{ flexDirection: Platform.OS === 'web' ? 'row' : 'column', gap: 16, marginVertical: 15 }}>
                    <LeafletMapView
                        environment={environment}
                        participants={participants}
                        activeParticipantId={activeParticipantId}
                        activeTourSteps={activeTourSteps}
                    />

                    <View style={{ flex: Platform.OS === 'web' ? 1 : 1, gap: 16, maxHeight: 300 }}>
                        <LogicalPlacesPanel logicalPlaces={logicalPlaces} places={environment.places} />

                        {/* PHYSICAL PLACES PANEL */}
                        <View style={styles.panelCard}>
                            <Text style={styles.panelTitle}>Physical Places</Text>
                            <ScrollView nestedScrollEnabled style={{ maxHeight: 230 }}>
                                {environment.places.map((place) => (
                                    <View key={place.id} style={styles.physicalCard}>
                                        <Text style={styles.physicalTitle}>{place.name || place.id}</Text>
                                        {place.attributes &&
                                            Object.entries(place.attributes)
                                                .filter(([_, attrVal]) => attrVal !== null && attrVal !== undefined && attrVal !== 'null')
                                                .map(([attrKey, attrVal]) => (
                                                    <View key={attrKey} style={styles.attributeRow}>
                                                        <Text style={styles.attrKey}>{attrKey}</Text>
                                                        <Text style={styles.attrVal}>{String(attrVal)}</Text>
                                                    </View>
                                                ))}
                                    </View>
                                ))}
                            </ScrollView>
                        </View>
                    </View>
                </View>

                {/* POI LIST AND ITINERARY DETAILS */}
                {activeTour && (
                    <View style={{ marginBottom: 20 }}>
                        <POIList tourResult={activeTour} places={environment.places} />
                    </View>
                )}

                {/* PARTICIPANT TABS */}
                <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.selectorContainer}>
                    {participants.map((p) => {
                        const isActive = p.id === activeParticipantId;
                        return (
                            <TouchableOpacity
                                key={p.id}
                                style={[styles.participantTab, isActive && styles.activeTab]}
                                onPress={() => setActiveParticipantId(p.id)}
                            >
                                <Text style={[styles.tabText, isActive && styles.activeTabText]}>👤 {p.name}</Text>
                                <Text style={styles.tabSubText}>Pos: {p.currentPlaceId || 'Unknown'}</Text>
                            </TouchableOpacity>
                        );
                    })}
                </ScrollView>

                {/* PENDING ACTIONS */}
                <PendingActionsCard
                    pendingActions={pendingActions}
                    loadingActions={loadingActions}
                    actionError={actionError}
                />

                {/* MANUAL MOVE */}
                <View style={styles.manualCard}>
                    <Text style={styles.manualTitle}>UPDATE PARTICIPANT POSITION</Text>
                    <View style={styles.manualRow}>
                        <TextInput
                            style={styles.manualInput}
                            placeholder="ID or Place Name (e.g., place1, place2)"
                            placeholderTextColor="#64748B"
                            value={manualInput}
                            onChangeText={setManualInput}
                        />
                        <TouchableOpacity
                            style={[styles.manualButton, isUpdatingPosition && { opacity: 0.6 }]}
                            onPress={handleManualMove}
                            disabled={isUpdatingPosition}
                        >
                            {isUpdatingPosition ? (
                                <ActivityIndicator size="small" color="#0F172A" />
                            ) : (
                                <Text style={styles.manualButtonText}>SET POSITION</Text>
                            )}
                        </TouchableOpacity>
                    </View>
                </View>

                {/* ACTUAL POSITION */}
                <View style={styles.currentCard}>
                    <Text style={styles.badgeText}>ACTUAL POSITION</Text>
                    <Text style={styles.placeTitle}>{currentPlace?.name || currentPlace?.id || 'Unknown Place'}</Text>
                </View>

                {/* CURRENT PLACE ATTRIBUTES */}
                {currentPlace && (
                    <View style={{ marginBottom: 20 }}>
                        <PlaceAttributesCard
                            placeName={currentPlace.name || currentPlace.id || 'Unknown'}
                            attributes={draftAttributes}
                            onAttributeChange={(k, v) => setDraftAttributes((prev) => ({ ...prev, [k]: v }))}
                        />
                        <TouchableOpacity
                            style={[
                                styles.moveButton,
                                { marginTop: 12, backgroundColor: '#38BDF8' },
                                isSavingAttributes && { opacity: 0.6 },
                            ]}
                            onPress={handleSaveAllAttributes}
                            disabled={isSavingAttributes}
                        >
                            {isSavingAttributes ? (
                                <ActivityIndicator size="small" color="#0F172A" />
                            ) : (
                                <Text style={[styles.moveButtonText, { color: '#0F172A' }]}>UPDATE ATTRIBUTES</Text>
                            )}
                        </TouchableOpacity>
                    </View>
                )}

                {/* ADJACENT PLACES */}
                {adjacentPlaces.length > 0 && (
                    <View style={{ marginBottom: 20 }}>
                        <Text style={styles.sectionHeader}>MOVE TO ADJACENT PLACE</Text>
                        <View style={styles.adjacentList}>
                            {adjacentPlaces.map((adj) => (
                                <TouchableOpacity
                                    key={adj.id}
                                    style={styles.adjacentCard}
                                    onPress={() => moveActiveParticipant(adj.id)}
                                >
                                    <View>
                                        <Text style={styles.adjacentName}>{adj.name || adj.id}</Text>
                                        <Text style={styles.adjacentSub}>Adjacent Location</Text>
                                    </View>
                                    <View style={styles.moveButton}>
                                        <Text style={styles.moveButtonText}>Move Here ➔</Text>
                                    </View>
                                </TouchableOpacity>
                            ))}
                        </View>
                    </View>
                )}
            </ScrollView>
        </View>
    );
};