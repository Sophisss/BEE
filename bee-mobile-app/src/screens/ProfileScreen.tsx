import React, { useState } from 'react';
import { Text, View, TouchableOpacity, ScrollView, Switch, Modal, FlatList } from 'react-native';
import Slider from '@react-native-community/slider';
import { styles } from './ProfileScreen.styles';
import { useTour } from '../context/TourContext'; // Import custom hook

interface ProfileScreenProps {
    onNavigateToMap: () => void;
}

const ARTISTIC_PREFERENCES = ['Rinascimento', 'Sculture', 'Barocco', 'Dipinti 600'];
const TOUR_LANGUAGES = ['Italiano (IT)', 'English (EN)', 'Español (ES)', 'Français (FR)'];

export const ProfileScreen = ({ onNavigateToMap }: ProfileScreenProps) => {
    const { tourConfig, updateTourConfig } = useTour(); // Access context state

    // Initialize states using saved context data or defaults
    const [tourType, setTourType] = useState<'guided' | 'autonomous'>(
        (tourConfig?.tourType as 'guided' | 'autonomous') || 'guided'
    );
    const [preferences, setPreferences] = useState<string[]>(
        tourConfig?.preferences || ['Rinascimento', 'Sculture']
    );
    const [needsAccessibility, setNeedsAccessibility] = useState<boolean>(
        tourConfig?.needsAccessibility ?? false
    );
    const [timeAvailable, setTimeAvailable] = useState<number>(
        tourConfig?.timeAvailable || 90
    );
    const [language, setLanguage] = useState<string>(
        tourConfig?.language || 'Italiano (IT)'
    );

    const [activeModal, setActiveModal] = useState<'none' | 'visitorType' | 'language'>('none');

    const togglePreference = (item: string) => {
        if (preferences.includes(item)) {
            setPreferences(preferences.filter((p) => p !== item));
        } else {
            setPreferences([...preferences, item]);
        }
    };

    const formatTime = (minutes: number) => {
        const h = Math.floor(minutes / 60);
        const m = minutes % 60;
        if (h === 0) return `${m}m`;
        if (m === 0) return `${h}h`;
        return `${h}h ${m}m`;
    };

    // Save data directly into global Context and navigate
    const handleStart = () => {
        updateTourConfig({
            tourType,
            timeAvailable,
            needsAccessibility,
            preferences,
            language,
        });
        onNavigateToMap();
    };

    return (
        <ScrollView
            style={styles.screenContainer}
            contentContainerStyle={[styles.contentContainer, { flexGrow: 1 }]}
        >
            <View style={styles.headerBox}>
                <Text style={styles.welcomeTitle}>🏛️ Benvenuto al Museo</Text>
                <Text style={styles.welcomeSubtitle}>Configura la tua visita personalizzata</Text>
            </View>

            {/* Tour Mode */}
            <Text style={styles.sectionTitle}>MODALITA' DI VISITA</Text>
            <View style={styles.segmentedControl}>
                <TouchableOpacity
                    style={[styles.segmentButton, tourType === 'guided' && styles.segmentActive]}
                    onPress={() => setTourType('guided')}
                >
                    <Text style={[styles.segmentText, tourType === 'guided' && styles.segmentTextActive]}>
                        Tour guidato
                    </Text>
                </TouchableOpacity>

                <TouchableOpacity
                    style={[styles.segmentButton, tourType === 'autonomous' && styles.segmentActive]}
                    onPress={() => setTourType('autonomous')}
                >
                    <Text style={[styles.segmentText, tourType === 'autonomous' && styles.segmentTextActive]}>
                        Tour autonomo
                    </Text>
                </TouchableOpacity>
            </View>

            {/* Preferences */}
            <Text style={styles.sectionTitle}>PREFERENZE ARTISTICHE</Text>
            <View style={styles.chipsContainer}>
                {ARTISTIC_PREFERENCES.map((pref) => {
                    const isSelected = preferences.includes(pref);
                    return (
                        <TouchableOpacity
                            key={pref}
                            style={[styles.chip, isSelected && styles.chipActive]}
                            onPress={() => togglePreference(pref)}
                        >
                            <Text style={[styles.chipText, isSelected && styles.chipTextActive]}>
                                {isSelected ? `✓  ${pref}` : pref}
                            </Text>
                        </TouchableOpacity>
                    );
                })}
            </View>

            {/* Accessibility Switch */}
            <View style={styles.toggleRow}>
                <View style={styles.toggleLabelGroup}>
                    <Text style={styles.toggleIcon}>♿</Text>
                    <Text style={styles.toggleText}>Accessibilità e mobilità</Text>
                </View>
                <Switch
                    value={needsAccessibility}
                    onValueChange={setNeedsAccessibility}
                    trackColor={{ false: '#1C293A', true: '#3388E6' }}
                    thumbColor="#FFF"
                />
            </View>

            {/* Time Slider */}
            <View style={styles.timeBox}>
                <View style={styles.timeHeader}>
                    <Text style={styles.timeLabel}>Tempo Disponibile</Text>
                    <Text style={styles.timeValue}>{formatTime(timeAvailable)}</Text>
                </View>
                <Slider
                    style={styles.nativeSlider}
                    minimumValue={15}
                    maximumValue={180}
                    step={15}
                    value={timeAvailable}
                    onValueChange={(val) => setTimeAvailable(val)}
                    minimumTrackTintColor="#E5A024"
                    maximumTrackTintColor="#1A2738"
                    thumbTintColor="#FFFFFF"
                />
            </View>

            {/* Language Selection */}
            <Text style={styles.sectionTitle}>LINGUA DEL TOUR</Text>
            <TouchableOpacity style={styles.dropdownBox} onPress={() => setActiveModal('language')}>
                <View style={styles.dropdownLeft}>
                    <Text style={styles.dropdownIcon}>🌐</Text>
                    <Text style={styles.dropdownText}>{language}</Text>
                </View>
                <Text style={styles.arrowIcon}>⌵</Text>
            </TouchableOpacity>

            <View style={{ flex: 1, minHeight: 30 }} />

            {/* Bottom Button */}
            <TouchableOpacity style={styles.startButton} onPress={handleStart}>
                <Text style={styles.startButtonText}>INIZIA TOUR</Text>
            </TouchableOpacity>

            {/* Modal */}
            {activeModal !== 'none' && (
                <Modal
                    visible={true}
                    transparent={true}
                    animationType="fade"
                    onRequestClose={() => setActiveModal('none')}
                >
                    <TouchableOpacity
                        style={styles.modalOverlay}
                        activeOpacity={1}
                        onPress={() => setActiveModal('none')}
                    >
                        <TouchableOpacity style={styles.modalContent} activeOpacity={1}>
                            <Text style={styles.modalTitle}>Seleziona Lingua</Text>

                            <FlatList
                                data={TOUR_LANGUAGES}
                                keyExtractor={(item) => item}
                                renderItem={({ item }) => (
                                    <TouchableOpacity
                                        style={styles.modalOption}
                                        onPress={() => {
                                            setLanguage(item);
                                            setActiveModal('none');
                                        }}
                                    >
                                        <Text style={styles.modalOptionText}>{item}</Text>
                                    </TouchableOpacity>
                                )}
                            />
                        </TouchableOpacity>
                    </TouchableOpacity>
                </Modal>
            )}
        </ScrollView>
    );
};