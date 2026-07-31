import React from 'react';
import { View, Text, TextInput, Switch, TouchableOpacity, StyleSheet } from 'react-native';

/**
 * Props definition for the PlaceAttributesCard component.
 */
interface PlaceAttributesCardProps {
    placeName: string;
    attributes?: Record<string, any>;
    availablePreferences?: string[];
    onAttributeChange: (key: string, newValue: any) => void;
}

const isBoolean = (value: any): boolean => typeof value === 'boolean' || value === 'true' || value === 'false';
const toBoolean = (value: any): boolean => typeof value === 'boolean' ? value : value === 'true';
const isNumeric = (value: any): boolean => !isNaN(Number(value)) && String(value).trim() !== '' && !isBoolean(value);

/**
 * Helper component that decides which input control (Chip Selector, Switch, Numeric Input, or Text Input) to render.
 */
const AttributeInput: React.FC<{
    attrKey: string;
    value: any;
    availablePreferences?: string[];
    onChange: (key: string, newValue: any) => void;
}> = ({ attrKey, value, availablePreferences = [], onChange }) => {

    // 1. Preferences array (Tags selector)
    if (Array.isArray(value) || attrKey === 'preferences') {
        const currentArray: string[] = Array.isArray(value) ? value : [];

        const togglePreference = (prefValue: string) => {
            const exists = currentArray.includes(prefValue);
            const updatedArray = exists
                ? currentArray.filter((item) => item !== prefValue)
                : [...currentArray, prefValue];

            onChange(attrKey, updatedArray);
        };

        if (availablePreferences.length === 0) {
            return (
                <Text style={styles.emptyText}>
                    No available tags found.
                </Text>
            );
        }

        return (
            <View style={styles.chipContainer}>
                {availablePreferences.map((prefTag) => {
                    const isSelected = currentArray.includes(prefTag);
                    return (
                        <TouchableOpacity
                            key={prefTag}
                            style={[styles.chip, isSelected && styles.chipSelected]}
                            onPress={() => togglePreference(prefTag)}
                            activeOpacity={0.7}
                        >
                            <Text style={[styles.chipText, isSelected && styles.chipTextSelected]}>
                                {isSelected ? '✓ ' : '+ '}{prefTag}
                            </Text>
                        </TouchableOpacity>
                    );
                })}
            </View>
        );
    }

    // 2. Boolean Switch (e.g. guided, needs_accessibility)
    if (isBoolean(value)) {
        const boolVal = toBoolean(value);
        return (
            <View style={styles.toggleContainer}>
                <Text style={[styles.toggleText, boolVal && styles.activeText]}>
                    {boolVal ? 'ACTIVE' : 'INACTIVE'}
                </Text>
                <Switch
                    value={boolVal}
                    onValueChange={(newVal) => onChange(attrKey, newVal)}
                    trackColor={{ false: '#334155', true: '#0F2942' }}
                    thumbColor={boolVal ? '#38BDF8' : '#94A3B8'}
                />
            </View>
        );
    }

    // 3. Numeric TextInput (e.g. available_time)
    if (isNumeric(value)) {
        return (
            <TextInput
                style={styles.input}
                keyboardType="numeric"
                value={String(value ?? '')}
                onChangeText={(newVal) => onChange(attrKey, newVal)}
                placeholderTextColor="#64748B"
            />
        );
    }

    // 4. Fallback: Standard Text TextInput (e.g. language or any free text attribute)
    return (
        <TextInput
            style={styles.input}
            value={String(value ?? '')}
            onChangeText={(newVal) => onChange(attrKey, newVal)}
            placeholderTextColor="#64748B"
        />
    );
};

/**
 * Component that renders an editable list of key-value attributes for a given Place or Participant.
 */
export const PlaceAttributesCard: React.FC<PlaceAttributesCardProps> = ({
                                                                            placeName,
                                                                            attributes = {},
                                                                            availablePreferences = [],
                                                                            onAttributeChange,
                                                                        }) => {
    if (Object.keys(attributes).length === 0) return null;

    return (
        <View style={styles.container}>
            <Text style={styles.badgeText}>
                PROFILE AND CONFIGURATION: {placeName.toUpperCase()}
            </Text>

            {Object.entries(attributes).map(([key, val]) => (
                <View key={key} style={styles.fieldRow}>
                    <Text style={styles.fieldLabel}>
                        {key.toUpperCase().replace(/_/g, ' ')}
                    </Text>
                    <AttributeInput
                        attrKey={key}
                        value={val}
                        availablePreferences={availablePreferences}
                        onChange={onAttributeChange}
                    />
                </View>
            ))}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        backgroundColor: '#1E293B',
        padding: 16,
        borderRadius: 12,
        marginBottom: 20,
        borderWidth: 1,
        borderColor: '#334155',
    },
    badgeText: {
        fontSize: 11,
        fontWeight: 'bold',
        color: '#94A3B8',
        letterSpacing: 1,
        marginBottom: 12,
    },
    fieldRow: {
        backgroundColor: '#0F172A',
        padding: 12,
        borderRadius: 8,
        marginBottom: 10,
    },
    fieldLabel: {
        fontSize: 11,
        fontWeight: 'bold',
        color: '#38BDF8',
        marginBottom: 8,
        letterSpacing: 0.8,
    },
    toggleContainer: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
    },
    toggleText: {
        fontSize: 11,
        color: '#94A3B8',
        fontWeight: '600',
    },
    activeText: {
        color: '#38BDF8',
    },
    input: {
        backgroundColor: '#334155',
        color: '#FFFFFF',
        padding: 10,
        borderRadius: 6,
        fontSize: 14,
    },
    chipContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
    },
    chip: {
        backgroundColor: '#334155',
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 16,
        borderWidth: 1,
        borderColor: '#475569',
    },
    chipSelected: {
        backgroundColor: '#0F2942',
        borderColor: '#38BDF8',
    },
    chipText: {
        color: '#94A3B8',
        fontSize: 12,
        fontWeight: '500',
    },
    chipTextSelected: {
        color: '#38BDF8',
        fontWeight: 'bold',
    },
    emptyText: {
        color: '#64748B',
        fontSize: 12,
        fontStyle: 'italic',
    },
});