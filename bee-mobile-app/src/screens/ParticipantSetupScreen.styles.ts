import {StyleSheet} from 'react-native';

export const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#0F172A'
    },
    center: {
        justifyContent: 'center',
        alignItems: 'center'
    },

    scrollArea: {
        flex: 1
    },
    content: {
        padding: 20
    },
    loadingText: {
        color: '#94A3B8',
        marginTop: 12,
        fontSize: 13
    },
    title: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#FFFFFF',
        marginBottom: 4
    },
    subtitle: {
        fontSize: 13,
        color: '#94A3B8',
        marginBottom: 20
    },
    sectionTitle: {
        fontSize: 11,
        fontWeight: 'bold',
        color: '#38BDF8',
        letterSpacing: 1,
        marginBottom: 8,
    },
    errorCard: {
        backgroundColor: '#451A1A',
        padding: 14,
        borderRadius: 10,
        borderWidth: 1,
        borderColor: '#F87171',
        marginBottom: 16,
    },
    errorTitle: {
        color: '#F87171',
        fontWeight: 'bold',
        fontSize: 13,
        marginBottom: 4
    },
    errorText: {
        color: '#FECACA',
        fontSize: 12,
        marginBottom: 10
    },
    retryButton: {
        backgroundColor: '#F87171',
        padding: 8,
        borderRadius: 6,
        alignItems: 'center'
    },
    retryButtonText: {
        color: '#FFFFFF',
        fontWeight: 'bold',
        fontSize: 11
    },
    dropdownWrapper: {
        marginBottom: 20,
        zIndex: 10,
    },
    dropdownHeader: {
        backgroundColor: '#1E293B',
        padding: 14,
        borderRadius: 10,
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        borderWidth: 1,
        borderColor: '#38BDF8',
    },
    dropdownHeaderText: {
        color: '#FFFFFF',
        fontWeight: 'bold',
        fontSize: 15,
    },
    dropdownArrow: {
        color: '#38BDF8',
        fontSize: 12,
    },
    dropdownList: {
        backgroundColor: '#1E293B',
        borderRadius: 10,
        marginTop: 4,
        borderWidth: 1,
        borderColor: '#334155',
        overflow: 'hidden',
    },
    dropdownItem: {
        padding: 12,
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        borderBottomWidth: 1,
        borderBottomColor: '#0F172A',
    },
    dropdownItemSelected: {
        backgroundColor: '#0F2942',
    },
    dropdownItemText: {
        color: '#94A3B8',
        fontSize: 14,
        fontWeight: '500',
    },
    dropdownItemTextSelected: {
        color: '#38BDF8',
        fontWeight: 'bold',
    },
    checkIcon: {
        color: '#38BDF8',
        fontWeight: 'bold',
    },
    cardContainer: {
        marginBottom: 10,
    },
    refreshButton: {
        backgroundColor: '#1E293B',
        paddingVertical: 10,
        paddingHorizontal: 16,
        borderRadius: 8,
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: '#334155',
        marginBottom: 20,
    },
    refreshButtonText: {
        color: '#38BDF8',
        fontWeight: '600',
        fontSize: 12,
        letterSpacing: 0.5,
    },
    footerContainer: {
        paddingHorizontal: 20,
        paddingVertical: 16,
        backgroundColor: '#0F172A',
        borderTopWidth: 1,
        borderTopColor: '#1E293B',
    },
    startButton: {
        backgroundColor: '#38BDF8',
        paddingVertical: 14,
        borderRadius: 10,
        alignItems: 'center',
    },
    disabledButton: {
        backgroundColor: '#334155',
        opacity: 0.6,
    },
    startButtonText: {
        color: '#0F172A',
        fontWeight: 'bold',
        fontSize: 15,
    }
});