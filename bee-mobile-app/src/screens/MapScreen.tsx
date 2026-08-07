import React, {useState, useEffect, useMemo, useRef, useCallback} from 'react';
import {View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, Platform, Modal} from 'react-native';
import {NativeStackScreenProps} from '@react-navigation/native-stack';
import {RootStackParamList, Participant, PendingAction} from '../types/navigation';
import {PlaceAttributesCard} from '../components/PlaceAttributesCard';
import {LeafletMapView} from '../components/LeafletMapView';
import {LogicalPlacesPanel} from '../components/LogicalPlacesPanel';
import {PendingActionsCard} from '../components/PendingActionsCard';
import {POIList} from '../components/POIList';
import {EnvironmentData, Place} from '../types/environment';
import {
    fetchPhysicalPlacesApi,
    fetchLogicalPlacesApi,
    fetchEdgesApi,
    fetchParticipantPositionApi,
    fetchParticipantTourApi,
    refreshParticipantsApi,
    refreshPendingActionsApi,
    updateParticipantPositionFromCoordinatesApi,
    updateParticipantPositionApi,
    updatePlaceAttributeApi,
    notifyEnvironmentChangeApi,
} from '../services/api';
import {
    buildTourStepsForMap,
    TourResult,
} from '../services/navigationService';
import {styles} from './MapScreen.styles';

type Props = NativeStackScreenProps<RootStackParamList, 'Map'>;

interface ExtendedPlace extends Place {
    attributes?: Record<string, any>;
    state?: string;
    status?: string;
    is_accessible?: boolean;
}

/**
 * Checks whether a place is closed or inaccessible based on its status and attributes.
 */
const isPlaceClosed = (place?: Place): boolean => {
    if (!place) return false;
    const extPlace = place as ExtendedPlace;

    if (extPlace.state?.trim().toLowerCase() === 'closed') return true;
    if (extPlace.status?.trim().toLowerCase() === 'closed') return true;
    if (extPlace.is_accessible === false) return true;

    const attrs = extPlace.attributes || {};
    const stateAttr = String(attrs.state || '').trim().toLowerCase();
    const statusAttr = String(attrs.status || '').trim().toLowerCase();

    if (stateAttr === 'closed' || statusAttr === 'closed') return true;
    if (attrs.is_accessible === false || attrs.accessible === false) return true;
    if (attrs.has_stairs === true) return true;

    return false;
};

export const MapScreen: React.FC<Props> = ({route}) => {
    const [participants, setParticipants] = useState<Participant[]>(route.params?.participants || []);
    const [activeParticipantId, setActiveParticipantId] = useState<string>(participants[0]?.id || '');
    const [environment, setEnvironment] = useState<EnvironmentData>({places: [], edges: []});
    const [positionInput, setPositionInput] = useState('');
    const [latitudeInput, setLatitudeInput] = useState('');
    const [longitudeInput, setLongitudeInput] = useState('');
    const [logicalPlaces, setLogicalPlaces] = useState<Record<string, string[]>>({});
    const [pendingActions, setPendingActions] = useState<PendingAction[]>([]);
    const [loadingActions, setLoadingActions] = useState<boolean>(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const [isUpdatingPosition, setIsUpdatingPosition] = useState<boolean>(false);
    const [draftAttributes, setDraftAttributes] = useState<Record<string, any>>({});
    const [isEditingAttributes, setIsEditingAttributes] = useState<boolean>(false);
    const [isSavingAttributes, setIsSavingAttributes] = useState<boolean>(false);
    const [activeTour, setActiveTour] = useState<TourResult | null>(null);
    const [isPlaceDropdownOpen, setIsPlaceDropdownOpen] = useState<boolean>(false);

    // --- TIMESTAMP REF TO PREVENT POLLING RACE CONDITIONS ---
    const lastManualMoveTimeRef = useRef<number>(0);

    // --- TRACK VISITED PLACES TO PRESERVE GRAY PATH HISTORY ---
    const [visitedPlaceIds, setVisitedPlaceIds] = useState<string[]>([]);

    // --- CUSTOM ALERT MODAL STATE ---
    const [alertModal, setAlertModal] = useState<{ visible: boolean; title: string; message: string }>({
        visible: false,
        title: '',
        message: '',
    });

    /**
     * Triggers a custom styled alert modal.
     */
    const triggerAlert = (title: string, message: string) => {
        setAlertModal({visible: true, title, message});
    };

    const activeParticipant = participants.find((p) => p.id === activeParticipantId);
    const currentPlace = environment.places.find((p) => p.id === activeParticipant?.currentPlaceId);
    const [selectedPlaceId, setSelectedPlaceId] = useState<string>('');

    // Synchronize selection state when the user's current place changes
    useEffect(() => {
        if (currentPlace?.id) {
            setSelectedPlaceId(currentPlace.id);
        }
    }, [currentPlace?.id]);

    // Track user movements in history to prevent line disappearing during polling
    useEffect(() => {
        if (activeParticipant?.currentPlaceId) {
            setVisitedPlaceIds((prev) => {
                if (!prev.includes(activeParticipant.currentPlaceId)) {
                    return [...prev, activeParticipant.currentPlaceId];
                }
                return prev;
            });
        }
    }, [activeParticipant?.currentPlaceId]);

    // Handle manual place selection from list
    const handleSelectPlaceFromList = (placeId: string) => {
        setSelectedPlaceId(placeId);
        setIsPlaceDropdownOpen(false);
    };

    const selectedPlace = useMemo(() => {
        if (!environment.places.length) return undefined;
        return environment.places.find((p) => p.id === selectedPlaceId) || currentPlace || environment.places[0];
    }, [environment.places, selectedPlaceId, currentPlace]);

    // Reset editing state and sync draft attributes when selecting a different place
    useEffect(() => {
        setIsEditingAttributes(false);
        if (selectedPlace) {
            setDraftAttributes(selectedPlace.attributes || {});
        }
    }, [selectedPlace?.id]);

    // Synchronize place attributes from server polling ONLY IF the user is not actively editing
    useEffect(() => {
        if (!selectedPlace || isEditingAttributes) return;
        setDraftAttributes(selectedPlace.attributes || {});
    }, [selectedPlace?.attributes, isEditingAttributes]);

    // Track position directly in refs to prevent React state closure staleness during polling
    const lastPositionRef = useRef<string | undefined>(undefined);
    const isJustMovedRef = useRef<boolean>(false);

    // Track unavailable places across polling cycles to detect reopening
    const unavailablePlacesRef = useRef<Set<string>>(new Set());

    // Initialize or sync position ref and visited steps when switching active participants
    useEffect(() => {
        lastPositionRef.current = activeParticipant?.currentPlaceId;
        setVisitedPlaceIds(activeParticipant?.currentPlaceId ? [activeParticipant.currentPlaceId] : []);
    }, [activeParticipantId]);

    // --- FETCH PARTICIPANT TOUR ---
    useEffect(() => {
        if (!activeParticipantId) {
            setActiveTour(null);
            return;
        }

        const loadTour = async () => {
            try {
                const tour = await fetchParticipantTourApi(activeParticipantId);
                setActiveTour(tour);
            } catch (error) {
                console.warn('Failed to load tour from backend:', error);
                setActiveTour(null);
            }
        };

        loadTour().catch(e => console.warn('Error loading tour:', e));
    }, [activeParticipantId]);

    // --- BUILD TOUR STEPS FOR MAP ---
    const activeTourSteps = useMemo(() => {
        if (!activeTour) return [];
        return buildTourStepsForMap(activeTour, environment.places);
    }, [activeTour, environment.places]);

    // --- COMBINE VISITED STEPS WITH CURRENT TOUR STEPS ---
    const combinedMapSteps = useMemo(() => {
        const visitedSteps = visitedPlaceIds
            .map((id) => environment.places.find((p) => p.id === id))
            .filter((p): p is Place => p !== undefined)
            .map((p, index) => {
                const extPlace = p as any;
                const coords = extPlace.coordinates;

                let lat = 0;
                let lng = 0;

                if (Array.isArray(coords) && coords.length >= 2) {
                    lat = Number(coords[0]);
                    lng = Number(coords[1]);
                } else if (coords && typeof coords === 'object') {
                    lat = Number(coords.latitude ?? coords.lat ?? 0);
                    lng = Number(coords.longitude ?? coords.lng ?? 0);
                } else {
                    lat = Number(extPlace.latitude ?? extPlace.lat ?? 0);
                    lng = Number(extPlace.longitude ?? extPlace.lng ?? 0);
                }

                return {
                    placeId: p.id,
                    latitude: lat,
                    longitude: lng,
                    order: index + 1,
                };
            });

        const newSteps = activeTourSteps.filter(
            (step) => !visitedPlaceIds.includes(step.placeId)
        );

        return [...visitedSteps, ...newSteps];
    }, [visitedPlaceIds, activeTourSteps, environment.places]);

    useEffect(() => {
        setPositionInput(activeParticipant?.currentPlaceId || '');
    }, [activeParticipantId, activeParticipant?.currentPlaceId]);

    /**
     * Evaluates logical conditions against physical place attributes.
     */
    const matchesLogicalCondition = (place: any, condition: any) => {
        const attrs = place?.attributes || {};
        const actualVal = attrs[condition.attribute];
        const expectedVal = condition.value;

        switch (condition.operator) {
            case '==':
                return actualVal === expectedVal;
            case '!=':
                return actualVal !== expectedVal;
            case '>':
            case '<':
            case '>=':
            case '<=': {
                const actualNumber = Number(actualVal);
                const expectedNumber = Number(expectedVal);
                if (!Number.isFinite(actualNumber) || !Number.isFinite(expectedNumber)) return false;
                if (condition.operator === '>') return actualNumber > expectedNumber;
                if (condition.operator === '<') return actualNumber < expectedNumber;
                if (condition.operator === '>=') return actualNumber >= expectedNumber;
                return actualNumber <= expectedNumber;
            }
            default:
                return false;
        }
    };

    // --- REFRESH PENDING ACTIONS HELPER ---
    const reloadPendingActions = useCallback(async (showLoading = false) => {
        if (!activeParticipantId) return;
        if (showLoading) setLoadingActions(true);
        setActionError(null);
        try {
            const actions = await refreshPendingActionsApi(activeParticipantId);
            setPendingActions(actions);
        } catch (error) {
            setActionError('Unable to retrieve pending actions');
            setPendingActions([]);
        } finally {
            if (showLoading) setLoadingActions(false);
        }
    }, [activeParticipantId]);

    // --- PERIODIC ENVIRONMENT & SMART TOUR RECALCULATION ---
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

                if (participantsList && participantsList.length > 0) {
                    const now = Date.now();
                    const isRecentlyMoved = now - lastManualMoveTimeRef.current < 4000;

                    setParticipants((prev) =>
                        participantsList.map((serverP) => {
                            if (serverP.id === activeParticipantId && isRecentlyMoved) {
                                const localP = prev.find((p) => p.id === activeParticipantId);
                                return {
                                    ...serverP,
                                    currentPlaceId: localP?.currentPlaceId || serverP.currentPlaceId,
                                };
                            }
                            return serverP;
                        })
                    );
                }

                if (rawLpData && rawLpData.length > 0) {
                    const normalizedLp: Record<string, string[]> = {};
                    rawLpData.forEach((logicalGroup: any) => {
                        const groupName = logicalGroup.name || 'Uncategorized';
                        const conditions = logicalGroup.conditions || [];
                        normalizedLp[groupName] = ppData
                            .filter((place: any) => conditions.every((cond: any) => matchesLogicalCondition(place, cond)))
                            .map((place: any) => place.id || place.name);
                    });
                    setLogicalPlaces(normalizedLp);
                }

                if (activeParticipantId) {
                    await reloadPendingActions(false);

                    const freshParticipant = participantsList?.find((p) => p.id === activeParticipantId);
                    const freshPos = freshParticipant?.currentPlaceId;

                    // Set of currently closed places in the environment
                    const currentUnavailableSet = new Set(
                        ppData.filter((p: Place) => isPlaceClosed(p)).map((p: Place) => p.id)
                    );

                    // Load initial tour if none exists
                    if (!activeTour) {
                        const initialTour = await fetchParticipantTourApi(activeParticipantId);
                        setActiveTour(initialTour);
                        lastPositionRef.current = freshPos;
                        unavailablePlacesRef.current = currentUnavailableSet;
                        return;
                    }

                    // 1. CHECK IF A PLACE ON THE REMAINING PLANNED ROUTE IS CLOSED
                    const remainingTourPlaceIds = activeTourSteps.map((s) => s.placeId);
                    const isBlockedOnRoute = remainingTourPlaceIds.some((placeId) => currentUnavailableSet.has(placeId));

                    // 2. CHECK IF PARTICIPANT IS OFF-COURSE (Only trigger if new position is NOT in planned route)
                    const positionChanged = freshPos !== undefined && freshPos !== lastPositionRef.current;
                    const isOnPlannedRoute = freshPos ? remainingTourPlaceIds.includes(freshPos) : true;
                    const isOffCourse = positionChanged && !isOnPlannedRoute;

                    // Recalculate tour only if necessary
                    if (isBlockedOnRoute || isOffCourse) {
                        const updatedTour = await fetchParticipantTourApi(activeParticipantId);
                        setActiveTour(updatedTour);

                        if (isBlockedOnRoute) {
                            triggerAlert(
                                '⚠️ Route Recalculated',
                                'A room along your route has been closed. An alternative path has been generated!'
                            );
                        } else if (isOffCourse) {
                            triggerAlert(
                                '🔄 Off Course',
                                'You moved to a different location. The itinerary has been updated from your new position!'
                            );
                        }
                    }

                    lastPositionRef.current = freshPos;
                    isJustMovedRef.current = false;
                    unavailablePlacesRef.current = currentUnavailableSet;
                }
            } catch (err) {
                console.warn('Backend fetch error:', err);
            }
        };

        fetchEnvironmentData().catch(e => console.warn('Error fetching environment data:', e));
        const interval = setInterval(fetchEnvironmentData, 3000);
        return () => clearInterval(interval);
    }, [activeParticipantId, activeTour, activeTourSteps, reloadPendingActions]);

    // --- FETCH PENDING ACTIONS ON PARTICIPANT CHANGE ---
    useEffect(() => {
        reloadPendingActions(true).catch(e => console.warn('Error reloading pending actions:', e));
    }, [activeParticipantId, activeParticipant?.currentPlaceId, reloadPendingActions]);

    const updateParticipantLocalPosition = (participantId: string, placeId: string) => {
        lastManualMoveTimeRef.current = Date.now();
        isJustMovedRef.current = true;
        setParticipants((prev) =>
            prev.map((participant) =>
                participant.id === participantId ? {...participant, currentPlaceId: placeId} : participant
            )
        );

        if (participantId === activeParticipantId) {
            setPositionInput(placeId);
        }
    };

    const moveActiveParticipant = async (targetPlaceId: string) => {
        if (!activeParticipantId) return;

        const targetPlace = environment.places.find((p) => p.id === targetPlaceId);
        if (isPlaceClosed(targetPlace)) {
            triggerAlert(
                '⛔ Relocation Denied',
                `The room "${targetPlace?.name || targetPlaceId}" is currently closed or inaccessible.`
            );
            return;
        }

        // Instantly mark the target place as visited to preserve gray path trace
        setVisitedPlaceIds((prev) =>
            prev.includes(targetPlaceId) ? prev : [...prev, targetPlaceId]
        );

        updateParticipantLocalPosition(activeParticipantId, targetPlaceId);
        setIsUpdatingPosition(true);

        try {
            await updateParticipantPositionApi(activeParticipantId, targetPlaceId);

            // Sync tour immediately to update POIList in real time
            const updatedTour = await fetchParticipantTourApi(activeParticipantId);
            setActiveTour(updatedTour);

            await reloadPendingActions(false);
        } catch (error) {
            triggerAlert('Synchronization Error', 'Position updated locally, but server sync failed.');
        } finally {
            setIsUpdatingPosition(false);
        }
    };

    const loadCurrentPosition = async () => {
        if (!activeParticipantId) return;

        try {
            setIsUpdatingPosition(true);
            const position = await fetchParticipantPositionApi(activeParticipantId);
            setPositionInput(position || '');
            updateParticipantLocalPosition(activeParticipantId, position || '');
            await reloadPendingActions(false);
        } catch (error) {
            triggerAlert('Synchronization Error', 'Unable to retrieve current position from server.');
        } finally {
            setIsUpdatingPosition(false);
        }
    };

    const handleManualMove = async () => {
        const trimmed = positionInput.trim().toLowerCase();
        if (!trimmed) return;

        const found = environment.places.find(
            (p) => p.id.toLowerCase() === trimmed || (p.name && p.name.toLowerCase().includes(trimmed))
        );

        if (found) {
            if (isPlaceClosed(found)) {
                triggerAlert(
                    '⛔ Relocation Denied',
                    `The room "${found.name || found.id}" is currently closed or inaccessible.`
                );
                setPositionInput(activeParticipant?.currentPlaceId || '');
                return;
            }
            await moveActiveParticipant(found.id);
        } else {
            triggerAlert('Place Not Found', `No matching places found for "${positionInput}".`);
            setPositionInput(activeParticipant?.currentPlaceId || '');
        }
    };

    const usePhoneGps = () => {
        if (typeof navigator === 'undefined' || !navigator.geolocation) {
            triggerAlert('GPS Unavailable', 'Geolocation is not supported in this environment.');
            return;
        }

        navigator.geolocation.getCurrentPosition(
            (pos) => {
                setLatitudeInput(pos.coords.latitude.toFixed(6));
                setLongitudeInput(pos.coords.longitude.toFixed(6));
            },
            (error) => {
                triggerAlert('GPS Error', error.message);
            },
            {enableHighAccuracy: true, timeout: 10000, maximumAge: 10000}
        );
    };

    const handleSetPositionFromCoordinates = async () => {
        if (!activeParticipantId) return;

        const latitude = Number(latitudeInput);
        const longitude = Number(longitudeInput);
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
            triggerAlert('Invalid Coordinates', 'Latitude and Longitude must be valid numbers.');
            return;
        }

        setIsUpdatingPosition(true);
        try {
            const resolvedPlaceId = await updateParticipantPositionFromCoordinatesApi(
                activeParticipantId,
                latitude,
                longitude
            );

            if (resolvedPlaceId) {
                const resolvedPlace = environment.places.find((p) => p.id === resolvedPlaceId);
                if (isPlaceClosed(resolvedPlace)) {
                    triggerAlert(
                        '⛔ Closed Location',
                        `The place resolved from coordinates ("${resolvedPlace?.name || resolvedPlaceId}") is closed. Relocation blocked.`
                    );
                    return;
                }

                setVisitedPlaceIds((prev) =>
                    prev.includes(resolvedPlaceId) ? prev : [...prev, resolvedPlaceId]
                );

                updateParticipantLocalPosition(activeParticipantId, resolvedPlaceId);

                const updatedTour = await fetchParticipantTourApi(activeParticipantId);
                setActiveTour(updatedTour);
            } else {
                updateParticipantLocalPosition(activeParticipantId, '');
            }

            await reloadPendingActions(false);
            if (!resolvedPlaceId) {
                triggerAlert('Position Update', 'No matching place found for the provided coordinates.');
            }
        } catch (error) {
            triggerAlert('Synchronization Error', 'Position sync failed on backend server.');
        } finally {
            setIsUpdatingPosition(false);
        }
    };

    const handleSaveAllAttributes = async () => {
        if (!selectedPlace) return;
        setIsSavingAttributes(true);
        const previousAttributes = {...(selectedPlace.attributes || {})};

        try {
            const parsedAttributes: Record<string, any> = {};

            for (const [key, rawValue] of Object.entries(draftAttributes)) {
                await updatePlaceAttributeApi(selectedPlace.id, key, rawValue);
                let parsedValue: any = rawValue;
                if (rawValue === 'true') parsedValue = true;
                else if (rawValue === 'false') parsedValue = false;
                else if (typeof rawValue === 'string' && !isNaN(Number(rawValue)) && rawValue.trim() !== '') {
                    parsedValue = Number(rawValue);
                }
                parsedAttributes[key] = parsedValue;
            }

            const updatedPlaceMock: ExtendedPlace = {
                ...selectedPlace,
                attributes: parsedAttributes,
            };
            const isNowClosed = isPlaceClosed(updatedPlaceMock);

            setEnvironment((prev) => ({
                ...prev,
                places: prev.places.map((p) => (p.id === selectedPlace.id ? {...p, attributes: parsedAttributes} : p)),
            }));

            if (isNowClosed && activeParticipant) {
                const reRoutedTour = await notifyEnvironmentChangeApi(activeParticipant, selectedPlace.id);
                setActiveTour(reRoutedTour);
            }

            await reloadPendingActions(false);
            setIsEditingAttributes(false);
        } catch (error) {
            setDraftAttributes(previousAttributes);
            triggerAlert('❌ Update Failed', 'Failed to save place attributes. Previous values restored.');
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
                <View style={{flexDirection: Platform.OS === 'web' ? 'row' : 'column', gap: 16, marginVertical: 15}}>
                    <LeafletMapView
                        environment={environment}
                        participants={participants}
                        activeParticipantId={activeParticipantId}
                        activeTourSteps={combinedMapSteps}
                        targetPlaceIds={activeTour?.targetPlaceIds}
                    />

                    <View style={{flex: Platform.OS === 'web' ? 1 : 1, gap: 16, maxHeight: 300}}>
                        <LogicalPlacesPanel logicalPlaces={logicalPlaces} places={environment.places}/>

                        {/* PHYSICAL PLACES PANEL */}
                        <View style={styles.panelCard}>
                            <Text style={styles.panelTitle}>Physical Places</Text>
                            <ScrollView nestedScrollEnabled style={{maxHeight: 230}}>
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

                {/* POI LIST */}
                {activeTour && (
                    <View style={{marginBottom: 20}}>
                        <POIList tourResult={activeTour} places={environment.places}/>
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

                {/* POSITION UPDATE PANEL */}
                <View style={styles.manualCard}>
                    <Text style={styles.manualTitle}>UPDATE PARTICIPANT POSITION</Text>
                    <View style={styles.manualRow}>
                        <TextInput
                            style={styles.manualInput}
                            placeholder="ID or Place Name (e.g., place1, place2)"
                            placeholderTextColor="#64748B"
                            value={positionInput}
                            onChangeText={setPositionInput}
                        />
                        <TouchableOpacity
                            style={[styles.manualButton, isUpdatingPosition && {opacity: 0.6}]}
                            onPress={handleManualMove}
                            disabled={isUpdatingPosition}
                        >
                            {isUpdatingPosition ? (
                                <ActivityIndicator size="small" color="#0F172A"/>
                            ) : (
                                <Text style={styles.manualButtonText}>SET POSITION</Text>
                            )}
                        </TouchableOpacity>
                    </View>
                    <View style={styles.manualActionRow}>
                        <TouchableOpacity
                            style={styles.secondaryButton}
                            onPress={loadCurrentPosition}
                            disabled={isUpdatingPosition}
                        >
                            <Text style={styles.secondaryButtonText}>LOAD CURRENT POSITION</Text>
                        </TouchableOpacity>
                    </View>
                    <Text style={[styles.manualTitle, {marginTop: 14}]}>UPDATE BY COORDINATES</Text>
                    <View style={styles.coordinateRow}>
                        <TextInput
                            style={styles.coordinateInput}
                            placeholder="Latitude"
                            placeholderTextColor="#64748B"
                            keyboardType="numeric"
                            value={latitudeInput}
                            onChangeText={setLatitudeInput}
                        />
                        <TextInput
                            style={styles.coordinateInput}
                            placeholder="Longitude"
                            placeholderTextColor="#64748B"
                            keyboardType="numeric"
                            value={longitudeInput}
                            onChangeText={setLongitudeInput}
                        />
                    </View>
                    <View style={styles.manualActionRow}>
                        <TouchableOpacity
                            style={styles.secondaryButton}
                            onPress={usePhoneGps}
                        >
                            <Text style={styles.secondaryButtonText}>USE PHONE GPS</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                            style={[styles.manualButton, isUpdatingPosition && {opacity: 0.6}]}
                            onPress={handleSetPositionFromCoordinates}
                            disabled={isUpdatingPosition}
                        >
                            {isUpdatingPosition ? (
                                <ActivityIndicator size="small" color="#0F172A"/>
                            ) : (
                                <Text style={styles.manualButtonText}>SET BY COORDINATES</Text>
                            )}
                        </TouchableOpacity>
                    </View>
                </View>

                {/* ACTUAL POSITION */}
                <View style={styles.currentCard}>
                    <Text style={styles.badgeText}>ACTUAL POSITION</Text>
                    <Text style={styles.placeTitle}>
                        {isPlaceClosed(currentPlace) ? `⛔ ${currentPlace?.name || currentPlace?.id} (CLOSED)` : (currentPlace?.name || currentPlace?.id || 'Unknown Place')}
                    </Text>
                </View>

                {/* PLACE ATTRIBUTES SELECTION & EDITING */}
                {environment.places.length > 0 && (
                    <View style={{marginBottom: 20}}>
                        <Text style={{color: '#90CAF9', fontSize: 14, fontWeight: 'bold', marginBottom: 8}}>
                            Select Place to Edit:
                        </Text>

                        {/* Dropdown / Selector List for Places */}
                        <TouchableOpacity
                            style={{
                                backgroundColor: '#1E293B',
                                padding: 12,
                                borderRadius: 8,
                                borderWidth: 1,
                                borderColor: '#334155',
                                marginBottom: 12,
                                flexDirection: 'row',
                                justifyContent: 'space-between',
                                alignItems: 'center'
                            }}
                            onPress={() => setIsPlaceDropdownOpen(!isPlaceDropdownOpen)}
                        >
                            <Text style={{color: '#F8FAFC', fontWeight: 'bold', fontSize: 15}}>
                                📍 {selectedPlace?.name || selectedPlace?.id || 'Select a place...'}
                            </Text>
                            <Text style={{color: '#94A3B8'}}>{isPlaceDropdownOpen ? '▲' : '▼'}</Text>
                        </TouchableOpacity>

                        {/* Dropdown Options List */}
                        {isPlaceDropdownOpen && (
                            <View style={{
                                backgroundColor: '#0F172A',
                                borderRadius: 8,
                                borderWidth: 1,
                                borderColor: '#334155',
                                maxHeight: 200,
                                marginBottom: 16,
                            }}>
                                <ScrollView nestedScrollEnabled style={{maxHeight: 200}}>
                                    {environment.places.map((place) => (
                                        <TouchableOpacity
                                            key={place.id}
                                            style={{
                                                padding: 12,
                                                borderBottomWidth: 1,
                                                borderBottomColor: '#1E293B',
                                                backgroundColor: place.id === selectedPlace?.id ? '#1E293B' : 'transparent',
                                            }}
                                            onPress={() => handleSelectPlaceFromList(place.id)}
                                        >
                                            <Text style={{
                                                color: place.id === selectedPlace?.id ? '#38BDF8' : '#E2E8F0',
                                                fontWeight: place.id === selectedPlace?.id ? 'bold' : 'normal'
                                            }}>
                                                {place.name || place.id} {isPlaceClosed(place) ? '⛔' : ''}
                                            </Text>
                                        </TouchableOpacity>
                                    ))}
                                </ScrollView>
                            </View>
                        )}

                        {/* Attributes Editor Card Component */}
                        {selectedPlace && (
                            <>
                                <PlaceAttributesCard
                                    placeName={selectedPlace.name || selectedPlace.id}
                                    attributes={draftAttributes}
                                    onAttributeChange={(key, value) => {
                                        setIsEditingAttributes(true);
                                        setDraftAttributes((prev) => ({
                                            ...prev,
                                            [key]: value,
                                        }));
                                    }}
                                />

                                <TouchableOpacity
                                    style={[
                                        styles.moveButton,
                                        {marginTop: 12, backgroundColor: '#38BDF8'},
                                        isSavingAttributes && {opacity: 0.6},
                                    ]}
                                    onPress={handleSaveAllAttributes}
                                    disabled={isSavingAttributes}
                                >
                                    {isSavingAttributes ? (
                                        <ActivityIndicator size="small" color="#0F172A"/>
                                    ) : (
                                        <Text style={[styles.moveButtonText, {color: '#0F172A'}]}>UPDATE
                                            ATTRIBUTES</Text>
                                    )}
                                </TouchableOpacity>
                            </>
                        )}
                    </View>
                )}

                {/* ADJACENT PLACES */}
                {adjacentPlaces.length > 0 && (
                    <View style={{marginBottom: 20}}>
                        <Text style={styles.sectionHeader}>MOVE TO ADJACENT PLACE</Text>
                        <View style={styles.adjacentList}>
                            {adjacentPlaces.map((adj) => {
                                const closed = isPlaceClosed(adj);
                                return (
                                    <TouchableOpacity
                                        key={adj.id}
                                        style={[
                                            styles.adjacentCard,
                                            closed && {opacity: 0.6, backgroundColor: '#fef2f2', borderColor: '#ef4444'}
                                        ]}
                                        onPress={() => moveActiveParticipant(adj.id)}
                                        disabled={closed || isUpdatingPosition}
                                    >
                                        <View>
                                            <Text style={[styles.adjacentName, closed && {color: '#b91c1c'}]}>
                                                {closed ? `⛔ ${adj.name || adj.id}` : (adj.name || adj.id)}
                                            </Text>
                                            <Text style={styles.adjacentSub}>
                                                {closed ? 'Closed / Inaccessible' : 'Adjacent Location'}
                                            </Text>
                                        </View>
                                        <View style={[styles.moveButton, closed && {backgroundColor: '#ef4444'}]}>
                                            <Text style={styles.moveButtonText}>
                                                {closed ? 'Closed ⛔' : 'Move Here ➔'}
                                            </Text>
                                        </View>
                                    </TouchableOpacity>
                                );
                            })}
                        </View>
                    </View>
                )}
            </ScrollView>

            {/* CUSTOM ALERT MODAL */}
            <Modal
                visible={alertModal.visible}
                transparent={true}
                animationType="fade"
                onRequestClose={() => setAlertModal((prev) => ({...prev, visible: false}))}
            >
                <View style={{
                    flex: 1,
                    backgroundColor: 'rgba(15, 23, 42, 0.75)',
                    justifyContent: 'center',
                    alignItems: 'center',
                    padding: 20,
                }}>
                    <View style={{
                        backgroundColor: '#1E293B',
                        borderRadius: 16,
                        padding: 24,
                        maxWidth: 400,
                        width: '100%',
                        borderWidth: 1,
                        borderColor: '#334155',
                        shadowColor: '#000',
                        shadowOffset: {width: 0, height: 4},
                        shadowOpacity: 0.3,
                        shadowRadius: 8,
                        elevation: 5,
                    }}>
                        <Text style={{fontSize: 18, fontWeight: 'bold', color: '#F8FAFC', marginBottom: 12}}>
                            {alertModal.title}
                        </Text>
                        <Text style={{fontSize: 14, color: '#94A3B8', marginBottom: 24, lineHeight: 20}}>
                            {alertModal.message}
                        </Text>
                        <View style={{flexDirection: 'row', justifyContent: 'flex-end'}}>
                            <TouchableOpacity
                                onPress={() => setAlertModal((prev) => ({...prev, visible: false}))}
                                style={{
                                    paddingVertical: 10,
                                    paddingHorizontal: 20,
                                    borderRadius: 8,
                                    backgroundColor: '#38BDF8',
                                }}
                            >
                                <Text style={{color: '#0F172A', fontWeight: 'bold'}}>OK</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>
        </View>
    );
};