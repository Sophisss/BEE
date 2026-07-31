import React, { useState } from 'react';
import { View, Text, TouchableOpacity, ScrollView } from 'react-native';
import { Place } from '../types/environment';
import { styles } from '../screens/MapScreen.styles';

/**
 * Props definition for the LogicalPlacesPanel component.
 */
interface LogicalPlacesPanelProps {
    logicalPlaces: Record<string, string[]>;
    places: Place[];
}

/**
 * Renders an individual logical place item displaying name and raw ID.
 */
const LogicalItem: React.FC<{ placeId: string; places: Place[] }> = ({ placeId, places }) => {
    const place = places.find((p) => p.id === placeId);

    return (
        <View style={styles.logicalItem}>
            <Text style={styles.logicalItemName}>
                {place ? place.name : placeId}
            </Text>
            <Text style={styles.logicalItemId}>{placeId}</Text>
        </View>
    );
};

/**
 * Renders an accordion category row with toggleable content.
 */
const AccordionCategory: React.FC<{
    category: string;
    placeIds: string[];
    places: Place[];
    isOpen: boolean;
    onToggle: () => void;
}> = ({ category, placeIds, places, isOpen, onToggle }) => {
    return (
        <View style={styles.accordionContainer}>
            <TouchableOpacity
                style={styles.accordionHeader}
                onPress={onToggle}
                activeOpacity={0.7}
            >
                <Text style={styles.accordionTitle}>
                    {isOpen ? '▾ ' : '▸ '} {category}
                </Text>
            </TouchableOpacity>

            {isOpen && (
                <View style={styles.accordionContent}>
                    {placeIds.length > 0 ? (
                        placeIds.map((id) => (
                            <LogicalItem key={id} placeId={id} places={places} />
                        ))
                    ) : (
                        <Text style={styles.emptyText}>No places assigned</Text>
                    )}
                </View>
            )}
        </View>
    );
};

/**
 * Panel component that groups physical places into collapsible logical categories.
 */
export const LogicalPlacesPanel: React.FC<LogicalPlacesPanelProps> = ({
                                                                          logicalPlaces,
                                                                          places,
                                                                      }) => {

    const [openCategories, setOpenCategories] = useState<Record<string, boolean>>({});

    const toggleCategory = (category: string) => {
        setOpenCategories((prev) => ({
            ...prev,
            [category]: !prev[category],
        }));
    };

    const hasLogicalPlaces = Object.keys(logicalPlaces).length > 0;

    return (
        <View style={styles.panelCard}>
            <Text style={styles.panelTitle}>Logical Places</Text>

            <ScrollView nestedScrollEnabled style={{ maxHeight: 240 }}>
                {hasLogicalPlaces ? (
                    Object.entries(logicalPlaces).map(([category, placeIds]) => (
                        <AccordionCategory
                            key={category}
                            category={category}
                            placeIds={placeIds}
                            places={places}
                            isOpen={openCategories[category]}
                            onToggle={() => toggleCategory(category)}
                        />
                    ))
                ) : (
                    <Text style={styles.emptyText}>No logical places loaded</Text>
                )}
            </ScrollView>
        </View>
    );
};