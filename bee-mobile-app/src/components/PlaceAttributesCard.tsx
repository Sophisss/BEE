import React from 'react';
import {View, Text, TextInput, Switch, StyleSheet} from 'react-native';

/**
 * Props definition for the PlaceAttributesCard component.
 */
interface PlaceAttributesCardProps {
    placeName: string;
    attributes?: Record<string, any>;
    onAttributeChange: (key: string, newValue: any) => void;
}

/**
 * Utility: Checks if a value represents a boolean type (boolean or string representation).
 */
const isBoolean = (value: any): boolean => {
    return (
        typeof value === 'boolean' ||
        value === 'true' ||
        value === 'false'
    );
};

/**
 * Utility: Converts a supported value to a strict boolean primitive.
 */
const toBoolean = (value: any): boolean => {
    if (typeof value === 'boolean') return value;
    return value === 'true';
};

/**
 * Utility: Checks if a value is strictly numeric (excluding booleans).
 */
const isNumeric = (value: any): boolean => {
    return !isNaN(Number(value)) && String(value).trim() !== '' && !isBoolean(value);
};

/**
 * Helper component that decides which input control (Switch, Numeric Input, or Text Input) to render.
 */
const AttributeInput: React.FC<{
    attrKey: string;
    value: any;
    onChange: (key: string, newValue: any) => void;
}> = ({attrKey, value, onChange}) => {
    // 1. Render Boolean Switch Control
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
                    trackColor={{false: '#334155', true: '#0F2942'}}
                    thumbColor={boolVal ? '#38BDF8' : '#94A3B8'}
                />
            </View>
        );
    }

    // 2. Render Numeric TextInput
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

    // 3. Fallback: Standard Text TextInput
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
 * Component that renders an editable list of key-value attributes for a given Place.
 */
export const PlaceAttributesCard: React.FC<PlaceAttributesCardProps> = ({
                                                                            placeName,
                                                                            attributes = {},
                                                                            onAttributeChange,
                                                                        }) => {
    if (Object.keys(attributes).length === 0) return null;

    return (
        <View style={styles.container}>
            <Text style={styles.badgeText}>
                ATTRIBUTES OF: {placeName.toUpperCase()}
            </Text>

            {Object.entries(attributes).map(([key, val]) => (
                <View key={key} style={styles.fieldRow}>
                    <Text style={styles.fieldLabel}>
                        {key.toUpperCase().replace(/_/g, ' ')}
                    </Text>
                    <AttributeInput
                        attrKey={key}
                        value={val}
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
        marginBottom: 6,
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
});