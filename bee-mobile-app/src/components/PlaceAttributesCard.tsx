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
    readOnly?: boolean;
}

// --- TYPE DETECTION HELPERS ---

const isBoolean = (value: any): boolean => typeof value === 'boolean' || value === 'true' || value === 'false';
const toBoolean = (value: any): boolean => typeof value === 'boolean' ? value : value === 'true';
const isNumeric = (value: any): boolean => !isNaN(Number(value)) && String(value).trim() !== '' && !isBoolean(value);

/**
 * Checks if a value is an Array or a string representation of an array (e.g. "[]").
 */
const isArrayLike = (val: any): boolean => {
    if (Array.isArray(val)) return true;
    if (typeof val === 'string') {
        const trimmed = val.trim();
        return trimmed.startsWith('[') && trimmed.endsWith(']');
    }
    return false;
};

/**
 * Safely parses any array-like value into a native JavaScript string array.
 */
const parseToArray = (val: any): string[] => {
    if (Array.isArray(val)) return val;
    if (typeof val === 'string') {
        try {
            const parsed = JSON.parse(val);
            if (Array.isArray(parsed)) return parsed;
        } catch {
            return [];
        }
    }
    return [];
};

/**
 * Helper component that decides which input control (Chip Selector, Switch, Numeric Input, or Text Input) to render.
 */
const AttributeInput: React.FC<{
    attrKey: string;
    value: any;
    availablePreferences?: string[];
    readOnly?: boolean;
    onChange: (key: string, newValue: any) => void;
}> = ({attrKey, value, availablePreferences = [], readOnly = false, onChange}) => {

    // 1. Array / Tags / Choices Selector (Handles both native arrays and "[]" strings)
    if (isArrayLike(value)) {
        const currentArray = parseToArray(value);

        const togglePreference = (prefValue: string) => {
            if (readOnly) return;
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
                            style={[
                                styles.chip,
                                isSelected && styles.chipSelected,
                                readOnly && styles.chipDisabled,
                            ]}
                            onPress={() => togglePreference(prefTag)}
                            disabled={readOnly}
                            activeOpacity={readOnly ? 1 : 0.7}
                        >
                            <Text style={[
                                styles.chipText,
                                isSelected && styles.chipTextSelected,
                                readOnly && styles.chipTextDisabled
                            ]}>
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
                    disabled={readOnly}
                    trackColor={{false: '#334155', true: '#0F2942'}}
                    thumbColor={boolVal ? '#38BDF8' : '#94A3B8'}
                />
            </View>
        );
    }

    // 3. Numeric TextInput
    if (isNumeric(value)) {
        return (
            <TextInput
                style={[styles.input, readOnly && styles.inputDisabled]}
                keyboardType="numeric"
                value={String(value ?? '')}
                onChangeText={(newVal) => onChange(attrKey, newVal)}
                editable={!readOnly}
                placeholderTextColor="#64748B"
            />
        );
    }

    // 4. Fallback: Standard Text TextInput (e.g. language or any free text attribute)
    return (
        <TextInput
            style={[styles.input, readOnly && styles.inputDisabled]}
            value={String(value ?? '')}
            onChangeText={(newVal) => onChange(attrKey, newVal)}
            editable={!readOnly}
            placeholderTextColor="#64748B"
        />
    );
};

/**
 * Component that renders an editable or read-only list of key-value attributes for a given Place or Participant.
 */
export const PlaceAttributesCard: React.FC<PlaceAttributesCardProps> = ({
                                                                            placeName,
                                                                            attributes = {},
                                                                            availablePreferences = [],
                                                                            onAttributeChange,
                                                                            readOnly = false
                                                                        }) => {
    if (Object.keys(attributes).length === 0) return null;

    return (
        <View style={[styles.container, readOnly && styles.containerReadOnly]}>
            <View style={styles.headerRow}>
                <Text style={styles.badgeText}>
                    PROFILE AND CONFIGURATION: {placeName.toUpperCase()}
                </Text>
                {readOnly && (
                    <Text style={styles.readOnlyBadge}>🔒 READ ONLY</Text>
                )}
            </View>

            {Object.entries(attributes).map(([key, val]) => (
                <View key={key} style={styles.fieldRow}>
                    <Text style={styles.fieldLabel}>
                        {key.toUpperCase().replace(/_/g, ' ')}
                    </Text>
                    <AttributeInput
                        attrKey={key}
                        value={val}
                        availablePreferences={availablePreferences}
                        readOnly={readOnly}
                        onChange={onAttributeChange}
                    />
                </View>
            ))}

            {readOnly && (
                <Text style={styles.readOnlyFooterText}>
                    Attributes have been locked for this active process instance.
                </Text>
            )}
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
    containerReadOnly: {
        borderColor: '#475569',
        backgroundColor: '#182232',
    },
    headerRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 12,
    },
    badgeText: {
        fontSize: 11,
        fontWeight: 'bold',
        color: '#94A3B8',
        letterSpacing: 1,
        flex: 1,
    },
    readOnlyBadge: {
        fontSize: 10,
        fontWeight: 'bold',
        color: '#F59E0B',
        backgroundColor: '#451A03',
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 4,
        overflow: 'hidden',
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
    inputDisabled: {
        backgroundColor: '#1E293B',
        color: '#64748B',
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
    chipDisabled: {
        opacity: 0.5,
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
    chipTextDisabled: {
        color: '#64748B',
    },
    emptyText: {
        color: '#64748B',
        fontSize: 12,
        fontStyle: 'italic',
    },
    readOnlyFooterText: {
        fontSize: 11,
        color: '#94A3B8',
        fontStyle: 'italic',
        textAlign: 'center',
        marginTop: 6,
    },
});