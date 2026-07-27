import React from 'react';
import { Text, View, TouchableOpacity, ScrollView } from 'react-native';
import { styles } from './MapScreen.styles';
import { useTour } from '../context/TourContext'; // Import custom hook

interface MapScreenProps {
    onBackToConfig: () => void;
}

const MOCK_ROUTE = [
    { id: '1', title: 'La Nascita di Venere', author: 'Sandro Botticelli', room: 'Sala 10 - Rinascimento' },
    { id: '2', title: 'Primavera', author: 'Sandro Botticelli', room: 'Sala 10 - Rinascimento' },
    { id: '3', title: 'David (Bozzetto)', author: 'Donatello', room: 'Sala 15 - Scultura' },
];

export const MapScreen = ({ onBackToConfig }: MapScreenProps) => {
    const { tourConfig } = useTour(); // Retrieve data directly from Context

    return (
        <View style={styles.screenContainer}>
            <View style={styles.header}>
                <TouchableOpacity style={styles.backButton} onPress={onBackToConfig}>
                    <Text style={styles.backButtonText}>← Modifica</Text>
                </TouchableOpacity>
                <Text style={styles.headerTitle}>Il Tuo Tour</Text>
                <View style={styles.headerPlaceholder} />
            </View>

            <ScrollView style={{ flex: 1 }}>
                <View style={styles.mapContainer}>
                    <Text style={{ fontSize: 32 }}>🗺️</Text>
                    <Text style={styles.mapPlaceholderText}>Mappa Interattiva Museo</Text>
                    <Text style={styles.mapSubtext}>Visualizzazione del percorso ottimizzato</Text>

                    <View style={styles.mapPin1}>
                        <Text style={styles.pinText}>📍 Tappa 1</Text>
                    </View>
                    <View style={styles.mapPin2}>
                        <Text style={styles.pinText}>📍 Tappa 2</Text>
                    </View>
                </View>

                <View style={styles.infoSection}>
                    <View style={styles.sectionHeader}>
                        <Text style={styles.sectionTitle}>TAPPE DELL'ITINERARIO</Text>
                        <Text style={styles.badgeInfo}>
                            ⏱️ {tourConfig?.timeAvailable || 90} min
                        </Text>
                    </View>

                    {MOCK_ROUTE.map((item, index) => (
                        <View key={item.id} style={styles.artCard}>
                            <View style={styles.badgeStep}>
                                <Text style={styles.badgeStepText}>{index + 1}</Text>
                            </View>
                            <View style={styles.artInfo}>
                                <Text style={styles.artTitle}>{item.title}</Text>
                                <Text style={styles.artAuthor}>{item.author}</Text>
                                <Text style={styles.artRoom}>📍 {item.room}</Text>
                            </View>
                            <Text style={styles.actionIcon}>›</Text>
                        </View>
                    ))}
                </View>
            </ScrollView>
        </View>
    );
};