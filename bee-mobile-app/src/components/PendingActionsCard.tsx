import React from 'react';
import { View, Text, ActivityIndicator, StyleSheet } from 'react-native';
import { PendingAction } from '../types/navigation';
import { styles } from '../screens/MapScreen.styles';

/**
 * Props definition for the PendingActionsCard component.
 */
interface PendingActionsCardProps {
    pendingActions: PendingAction[];
    loadingActions: boolean;
    actionError: string | null;
}

/**
 * Component that displays a list of pending actions or tasks for the active participant.
 */
export const PendingActionsCard: React.FC<PendingActionsCardProps> = ({
                                                                          pendingActions,
                                                                          loadingActions,
                                                                          actionError,
                                                                      }) => {
    return (
        <View style={styles.actionsCard}>
            <View style={styles.actionsHeader}>
                <Text style={styles.actionsTitle}>PENDING ACTIONS</Text>
                {loadingActions && <ActivityIndicator size="small" color="#38BDF8" />}
            </View>

            {actionError ? (
                <Text style={localStyles.errorText}>⚠️ {actionError}</Text>
            ) : pendingActions.length > 0 ? (
                <View style={styles.actionsList}>
                    {pendingActions.map((act, index) => {
                        // Generate a unique fallback key to prevent React key collision issues
                        const uniqueKey = act.activityId
                            ? `${act.activityId}-${index}`
                            : `${act.action || 'act'}-${index}`;

                        return (
                            <View key={uniqueKey} style={styles.actionItem}>
                                <Text style={localStyles.typeText}>
                                    {(act.type || 'task').toUpperCase()}
                                </Text>
                                <Text style={styles.actionName}>
                                    {act.message || `Execute action: ${act.action || 'unknown'}`}
                                </Text>

                            </View>
                        );
                    })}
                </View>
            ) : (
                <Text style={styles.emptyActionsText}>
                    No pending actions for this participant.
                </Text>
            )}
        </View>
    );
};

/**
 * Local fallback styles for components specific to this card (e.g., error formatting).
 */
const localStyles = StyleSheet.create({
    errorText: {
        color: '#F87171',
        fontSize: 13,
    },
    typeText: {
        color: '#38BDF8',
        fontSize: 10,
        fontWeight: '700',
        marginBottom: 2,
    },
});