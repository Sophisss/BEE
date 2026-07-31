import React, {useState, useEffect, useMemo} from 'react';
import {View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, Alert} from 'react-native';
import {NativeStackScreenProps} from '@react-navigation/native-stack';
import {RootStackParamList, Participant} from '../types/navigation';
import {PlaceAttributesCard} from '../components/PlaceAttributesCard';
import {
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
 * Subcomponent: Renders connection error alert with a retry button.
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
    const [isSaving, setIsSaving] = useState<boolean>(false);

    /** Active participant object computed from state selection */
    const currentParticipant = participants.find((p) => p.id === selectedParticipantId);

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
            }));

            setParticipants(sanitizedParticipants);

            if (sanitizedParticipants.length > 0 && !selectedParticipantId) {
                setSelectedParticipantId(sanitizedParticipants[0].id);
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

    useEffect(() => {
        loadScreenData().then(_ => console.log('ParticipantSetupScreen data loaded.'));
    }, []);

    /**
     * Updates an attribute value locally for the active participant
     * and flags the participant ID as modified.
     */
    const handleAttributeChange = (attrKey: string, newValue: any) => {
        if (!selectedParticipantId) return;

        // Mark current participant ID as modified
        setModifiedParticipantIds((prev) => new Set(prev).add(selectedParticipantId));

        // Update local participant state in place
        setParticipants((prev) =>
            prev.map((participant) => {
                if (participant.id === selectedParticipantId) {
                    return {
                        ...participant,
                        attributes: {
                            ...participant.attributes,
                            [attrKey]: newValue,
                        },
                    };
                }
                return participant;
            })
        );
    };

    /**
     * Persists modified participant attributes to backend API
     * and navigates to the interactive MapScreen.
     */
    const handleStartSimulation = async () => {
        if (!selectedParticipantId || participants.length === 0) return;

        try {
            setIsSaving(true);

            const changedParticipants = participants.filter((p) => modifiedParticipantIds.has(p.id));

            if (changedParticipants.length > 0) {
                await Promise.all(
                    changedParticipants.map((participant) =>
                        updateParticipantAttributesApi(participant.id, participant.attributes || {})
                    )
                );
                console.log(`Updated ${changedParticipants.length} modified participant(s) in engine.`);
            } else {
                console.log('No attributes were modified. Skipping update calls.');
            }

            navigation.navigate('Map', {participants});
        } catch (error: any) {
            console.error('Error saving attributes to engine:', error);
            Alert.alert(
                'Sync Error',
                `⚠️ Unable to synchronize attributes with engine:\n${error?.message || 'Network request failed'}`
            );
        } finally {
            setIsSaving(false);
        }
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
                    selectedParticipantId={selectedParticipantId}
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
                            placeName={currentParticipant.name}
                            attributes={currentParticipant.attributes}
                            availablePreferences={availablePreferences}
                            onAttributeChange={handleAttributeChange}
                        />
                    </View>
                )}
            </ScrollView>

            <View style={styles.footerContainer}>
                <TouchableOpacity
                    style={[
                        styles.startButton,
                        (!selectedParticipantId || participants.length === 0 || isSaving) && styles.disabledButton,
                    ]}
                    onPress={handleStartSimulation}
                    disabled={!selectedParticipantId || participants.length === 0 || isSaving}
                    activeOpacity={0.8}
                >
                    {isSaving ? (
                        <ActivityIndicator color="#0F172A"/>
                    ) : (
                        <Text style={styles.startButtonText}>CONFIRM AND OPEN MAP ➔</Text>
                    )}
                </TouchableOpacity>
            </View>
        </View>
    );
};