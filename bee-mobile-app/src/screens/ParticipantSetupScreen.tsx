import React, { useState, useMemo, useCallback } from 'react';
import { View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, Modal } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList, Participant } from '../types/navigation';
import { PlaceAttributesCard } from '../components/PlaceAttributesCard';
import { useFocusEffect } from '@react-navigation/native';
import {
    completeTaskForParticipantApi,
    defaultBaseUrl,
    fetchPhysicalPlacesApi,
    getBaseUrl,
    refreshParticipantsApi,
    setBaseUrl,
    updateParticipantAttributesApi,
} from '../services/api';
import {styles} from './ParticipantSetupScreen.styles';

type Props = NativeStackScreenProps<RootStackParamList, 'ParticipantSetup'>;

/**
 * Extracts unique tag preferences from a list of physical places.
 * Handles tags provided as a comma-separated string or as an array of strings.
 *
 * @param physicalPlaces - Array of physical place objects retrieved from the engine API.
 * @returns An array of unique, trimmed preference tag strings.
 */
const extractPreferencesFromPlaces = (physicalPlaces: any[] = []): string[] => {
    const preferencesSet = new Set<string>();

    physicalPlaces.forEach((place) => {
        const rawTags = place?.attributes?.tags || place?.tags;

        if (typeof rawTags === 'string' && rawTags.trim() !== '') {
            rawTags.split(',').forEach((tag) => {
                const cleaned = tag.trim();
                if (cleaned) preferencesSet.add(cleaned);
            });
        } else if (Array.isArray(rawTags)) {
            rawTags.forEach((tag) => {
                if (typeof tag === 'string' && tag.trim()) {
                    preferencesSet.add(tag.trim());
                }
            });
        }
    });

    return Array.from(preferencesSet);
};

/**
 * Sanitizes participant names by removing text enclosed in parentheses
 * and stray "null" strings (case-insensitive).
 *
 * @param name - The raw participant name string from backend.
 * @returns Cleaned string suitable for display in UI.
 */
const cleanParticipantName = (name?: string): string => {
    if (!name) return '';
    return name
        .replace(/\s*\([\s\S]*?\)/g, '')
        .replace(/\bnull\b/gi, '')
        .trim();
};

/**
 * Normalizes participant attributes to ensure consistent access.
 * Some participants may have attributes nested under an 'attributes' key,
 * while others may have them directly on the participant object.
 *
 * @param participant - The participant object to extract attributes from.
 * @returns A flat object containing the participant's attributes.
 */
const getNormalizedAttributes = (participant?: Participant): Record<string, any> => {
    if (!participant || !participant.attributes) return {};
    const rawAttributes = participant.attributes as any;
    if (rawAttributes.attributes && typeof rawAttributes.attributes === 'object') {
        return rawAttributes.attributes;
    }
    return rawAttributes;
};

/**
 * Cleans internal system attributes (e.g., 'completed', 'status', 'isCompleted')
 * so they are not rendered as dynamic inputs inside the PlaceAttributesCard UI.
 *
 * @param participant - The participant object to extract UI attributes from.
 * @returns A clean object containing only participant preferences and user attributes.
 */
const getCleanAttributesForUI = (participant?: Participant): Record<string, any> => {
    const attrs = getNormalizedAttributes(participant);
    const { completed, status, isCompleted, ...cleanAttrs } = attrs;
    return cleanAttrs;
};

/**
 * Renders a card displaying a connection error message and a retry button.
 *
 * @param errorMsg - The error message to display.
 * @param onRetry - Function to call when the retry button is pressed.
 */
const ConnectionErrorCard: React.FC<{ errorMsg: string; onRetry: () => void }> = ({errorMsg, onRetry}) => (
    <View style={styles.errorCard}>
        <Text style={styles.errorTitle}>⚠️ Connection Error</Text>
        <Text style={styles.errorText}>{errorMsg}</Text>
        <TouchableOpacity style={styles.retryButton} onPress={onRetry} activeOpacity={0.7}>
            <Text style={styles.retryButtonText}>TRY AGAIN</Text>
        </TouchableOpacity>
    </View>
);

/**
 * Subcomponent: Custom collapsible dropdown selector for process participants.
 */
const ParticipantDropdown: React.FC<{
    participants: Participant[];
    selectedParticipantId: string;
    isOpen: boolean;
    onToggle: () => void;
    onSelect: (id: string) => void;
    currentParticipantName?: string;
}> = ({participants, selectedParticipantId, isOpen, onToggle, onSelect, currentParticipantName}) => (
    <View style={styles.dropdownWrapper}>
        <TouchableOpacity style={styles.dropdownHeader} onPress={onToggle} activeOpacity={0.8}>
            <Text style={styles.dropdownHeaderText}>
                👤 {currentParticipantName || 'No participant found'}
            </Text>
            <Text style={styles.dropdownArrow}>{isOpen ? '▲' : '▼'}</Text>
        </TouchableOpacity>

        {isOpen && (
            <View style={styles.dropdownList}>
                {participants.length === 0 ? (
                    <View style={styles.dropdownItem}>
                        <Text style={styles.dropdownItemText}>No participants available</Text>
                    </View>
                ) : (
                    participants.map((p) => {
                        const isSelected = p.id === selectedParticipantId;
                        return (
                            <TouchableOpacity
                                key={p.id}
                                style={[styles.dropdownItem, isSelected && styles.dropdownItemSelected]}
                                onPress={() => onSelect(p.id)}
                                activeOpacity={0.7}
                            >
                                <Text style={[styles.dropdownItemText, isSelected && styles.dropdownItemTextSelected]}>
                                    {p.name}
                                </Text>
                                {isSelected && <Text style={styles.checkIcon}>✓</Text>}
                            </TouchableOpacity>
                        );
                    })
                )}
            </View>
        )}
    </View>
);

/**
 * Setup Screen for viewing and configuring process participant attributes before launching the simulation map.
 */
export const ParticipantSetupScreen: React.FC<Props> = ({navigation}) => {
    const [loading, setLoading] = useState<boolean>(true);
    const [errorMsg, setErrorMsg] = useState<string | null>(null);
    const [baseUrl, setBaseUrlInput] = useState<string>(getBaseUrl());

    const [participants, setParticipants] = useState<Participant[]>([]);
    const [selectedParticipantId, setSelectedParticipantId] = useState<string>('');
    const [isDropdownOpen, setIsDropdownOpen] = useState<boolean>(false);

    const [modifiedParticipantIds, setModifiedParticipantIds] = useState<Set<string>>(new Set());
    const [confirmedParticipantIds, setConfirmedParticipantIds] = useState<Set<string>>(new Set());

    const [isSaving, setIsSaving] = useState<boolean>(false);
    const [isConfirmModalVisible, setIsConfirmModalVisible] = useState<boolean>(false);

    const currentParticipant = useMemo(() => {
        if (!participants || participants.length === 0) return undefined;
        return participants.find((p) => p.id === selectedParticipantId) || participants[0];
    }, [participants, selectedParticipantId]);

    const isCurrentParticipantReadOnly = useMemo(() => {
        if (!currentParticipant) return false;

        if (confirmedParticipantIds.has(currentParticipant.id)) {
            return true;
        }

        const rawP = currentParticipant as any;
        const attrs = getNormalizedAttributes(currentParticipant);

        return !!(
            rawP.completed ||
            rawP.isCompleted ||
            rawP.status === 'COMPLETED' ||
            attrs.completed === true ||
            attrs.status === 'COMPLETED' ||
            (rawP.taskId === null || rawP.taskId === undefined && rawP.hasTask === false)
        );
    }, [currentParticipant, confirmedParticipantIds]);

    const [physicalPlaces, setPhysicalPlaces] = useState<any[]>([]);
    const availablePreferences = useMemo(() => {
        return extractPreferencesFromPlaces(physicalPlaces);
    }, [physicalPlaces]);

    /**
     * Fetches participants and physical places from backend APIs, sanitizes names,
     * and initializes state for the dropdown and attribute editor.
     * Handles errors and sets loading state accordingly.
     */
    const loadScreenData = async () => {
        try {
            setLoading(true);
            setErrorMsg(null);

            const [loadedParticipants, places] = await Promise.all([
                refreshParticipantsApi(),
                fetchPhysicalPlacesApi(),
            ]);

            const sanitizedParticipants = loadedParticipants.map((p) => ({
                ...p,
                name: cleanParticipantName(p.name) || p.id,
                attributes: getNormalizedAttributes(p),
            }));

            setParticipants(sanitizedParticipants);

            if (sanitizedParticipants.length > 0) {
                setSelectedParticipantId((prevId) => {
                    const exists = sanitizedParticipants.some((p) => p.id === prevId);
                    return exists ? prevId : sanitizedParticipants[0].id;
                });
            }

            if (Array.isArray(places)) {
                setPhysicalPlaces(places);
            }
        } catch (error: any) {
            console.error('Error retrieving process participants:', error);
            setErrorMsg(error.message || 'Unable to connect to process engine');
        } finally {
            setLoading(false);
        }
    };

    useFocusEffect(
        useCallback(() => {
            loadScreenData().then(_ => {}).catch(e => console.error('Error loading screen data on focus:', e));
        }, [])
    );

    /**
     * Updates an attribute value locally for the active participant
     * and flags the participant ID as modified.
     */
    const handleAttributeChange = (attrKey: string, newValue: any) => {
        const activeId = currentParticipant?.id;
        if (!activeId || isCurrentParticipantReadOnly) return;

        setModifiedParticipantIds((prev) => new Set(prev).add(activeId));

        // Update local participant state in place
        setParticipants((prev) =>
            prev.map((participant) => {
                if (participant.id === activeId) {
                    const currentAttributes = getNormalizedAttributes(participant);
                    return {
                        ...participant,
                        attributes: {
                            ...currentAttributes,
                            [attrKey]: newValue,
                        },
                    };
                }
                return participant;
            })
        );
    };

    const executeStartSimulation = async () => {
        const activeId = currentParticipant?.id;
        if (!activeId || participants.length === 0) return;

        console.log('🚀 Executing simulation start for:', activeId);

        try {
            setIsSaving(true);
            setIsConfirmModalVisible(false);

            const currentAttrs = getNormalizedAttributes(currentParticipant);
            const updatedAttributesWithCompletion = {
                ...currentAttrs,
                completed: true
            };

            const targetParticipants = participants.filter(
                (p) => p.id === activeId || modifiedParticipantIds.has(p.id)
            );

            let updatedParticipantsList = [...participants];

            if (targetParticipants.length > 0) {
                const updateResults = await Promise.all(
                    targetParticipants.map((participant) => {
                        const isTargetActive = participant.id === activeId;
                        const attrsToSave = isTargetActive
                            ? updatedAttributesWithCompletion
                            : getNormalizedAttributes(participant);
                        return updateParticipantAttributesApi(participant.id, attrsToSave);
                    })
                );

                updatedParticipantsList = updatedParticipantsList.map((p) => {
                    const updated = updateResults.find((u) => u && u.id === p.id);
                    return updated || p;
                });
            }

            try {
                await completeTaskForParticipantApi(activeId);
            } catch (taskErr: any) {
                console.warn('Task completing warning (might be already completed):', taskErr?.message);
            }

            setConfirmedParticipantIds((prev) => new Set(prev).add(activeId));

            navigation.navigate('Map', { participants: updatedParticipantsList });
        } catch (error: any) {
            setConfirmedParticipantIds((prev) => {
                const next = new Set(prev);
                next.delete(activeId);
                return next;
            });

            setErrorMsg(`⚠️ Unable to synchronize attributes with engine: ${error?.message || 'Network request failed'}`);
        } finally {
            setIsSaving(false);
        }
    };

    const handleStartSimulation = () => {
        const activeId = currentParticipant?.id;
        console.log('Start button pressed. Active ID:', activeId, 'ReadOnly:', isCurrentParticipantReadOnly);

        if (!activeId || participants.length === 0) return;

        if (isCurrentParticipantReadOnly) {
            navigation.navigate('Map', { participants });
            return;
        }

        setIsConfirmModalVisible(true);
    };

    if (loading) {
        return (
            <View style={[styles.container, styles.center]}>
                <ActivityIndicator size="large" color="#38BDF8"/>
                <Text style={styles.loadingText}>
                    Retrieving participants from the BPMN process...
                </Text>
            </View>
        );
    }

    return (
        <View style={styles.container}>
            <ScrollView style={styles.scrollArea} contentContainerStyle={styles.content}>
                <View style={styles.connectionCard}>
                    <Text style={styles.connectionTitle}>Connection</Text>
                    <Text style={styles.connectionLabel}>Engine Base URL</Text>
                    <TextInput
                        style={styles.connectionInput}
                        value={baseUrl}
                        onChangeText={(value) => {
                            setBaseUrlInput(value);
                            setBaseUrl(value);
                        }}
                        autoCapitalize="none"
                        autoCorrect={false}
                        placeholder={defaultBaseUrl()}
                        placeholderTextColor="#64748B"
                    />
                    <TouchableOpacity
                        style={styles.refreshButton}
                        onPress={loadScreenData}
                        activeOpacity={0.7}
                    >
                        <Text style={styles.refreshButtonText}>REFRESH PARTICIPANTS</Text>
                    </TouchableOpacity>
                </View>

                {/* Header Information */}
                <Text style={styles.title}>Process Participants</Text>
                <Text style={styles.subtitle}>
                    Select a participant from the process to view and modify their attributes.
                </Text>

                {/* Connection Error Banner */}
                {errorMsg && (
                    <ConnectionErrorCard
                        errorMsg={errorMsg}
                        onRetry={loadScreenData}
                    />
                )}

                {/* Dropdown Selector Section */}
                <Text style={styles.sectionTitle}>SELECT PARTICIPANT:</Text>
                <ParticipantDropdown
                    participants={participants}
                    selectedParticipantId={currentParticipant?.id || ''}
                    isOpen={isDropdownOpen}
                    onToggle={() => setIsDropdownOpen(!isDropdownOpen)}
                    onSelect={(id) => {
                        setSelectedParticipantId(id);
                        setIsDropdownOpen(false);
                    }}
                    currentParticipantName={currentParticipant?.name}
                />

                {/* Participant Attributes Card Editor */}
                {currentParticipant && (
                    <View style={styles.cardContainer}>
                        <PlaceAttributesCard
                            key={currentParticipant.id}
                            placeName={currentParticipant.name}
                            attributes={getCleanAttributesForUI(currentParticipant)}
                            availablePreferences={availablePreferences}
                            onAttributeChange={handleAttributeChange}
                            readOnly={isCurrentParticipantReadOnly}
                        />
                    </View>
                )}
            </ScrollView>

            <View style={styles.footerContainer}>
                <TouchableOpacity
                    style={[
                        styles.startButton,
                        (!currentParticipant || participants.length === 0 || isSaving) && styles.disabledButton,
                    ]}
                    onPress={handleStartSimulation}
                    disabled={!currentParticipant || participants.length === 0 || isSaving}
                    activeOpacity={0.8}
                >
                    {isSaving ? (
                        <ActivityIndicator color="#0F172A"/>
                    ) : (
                        <Text style={styles.startButtonText}>
                            {isCurrentParticipantReadOnly ? 'OPEN MAP ➔' : 'CONFIRM AND OPEN MAP ➔'}
                        </Text>
                    )}
                </TouchableOpacity>
            </View>

            <Modal
                visible={isConfirmModalVisible}
                transparent={true}
                animationType="fade"
                onRequestClose={() => setIsConfirmModalVisible(false)}
            >
                <View style={{
                    flex: 1,
                    backgroundColor: 'rgba(15, 23, 42, 0.75)',
                    justifyContent: 'center',
                    alignItems: 'center',
                    padding: 20
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
                        shadowOffset: { width: 0, height: 4 },
                        shadowOpacity: 0.3,
                        shadowRadius: 8,
                        elevation: 5
                    }}>
                        <Text style={{ fontSize: 18, fontWeight: 'bold', color: '#F8FAFC', marginBottom: 12 }}>
                            Confirm Participant Attributes
                        </Text>
                        <Text style={{ fontSize: 14, color: '#94A3B8', marginBottom: 24, lineHeight: 20 }}>
                            Are you sure you want to finalize settings for <Text style={{ color: '#38BDF8', fontWeight: '600' }}>"{currentParticipant?.name}"</Text>?{'\n\n'}
                            Once confirmed, these attributes will be sent to the process engine.
                        </Text>

                        <View style={{ flexDirection: 'row', justifyContent: 'flex-end', gap: 12 }}>
                            <TouchableOpacity
                                onPress={() => setIsConfirmModalVisible(false)}
                                style={{
                                    paddingVertical: 10,
                                    paddingHorizontal: 16,
                                    borderRadius: 8,
                                    backgroundColor: '#334155'
                                }}
                            >
                                <Text style={{ color: '#F8FAFC', fontWeight: '600' }}>Cancel</Text>
                            </TouchableOpacity>

                            <TouchableOpacity
                                onPress={executeStartSimulation}
                                disabled={isSaving}
                                style={{
                                    paddingVertical: 10,
                                    paddingHorizontal: 16,
                                    borderRadius: 8,
                                    backgroundColor: '#38BDF8',
                                    flexDirection: 'row',
                                    alignItems: 'center'
                                }}
                            >
                                {isSaving ? (
                                    <ActivityIndicator size="small" color="#0F172A" />
                                ) : (
                                    <Text style={{ color: '#0F172A', fontWeight: 'bold' }}>Yes, Confirm</Text>
                                )}
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>
        </View>
    );
};