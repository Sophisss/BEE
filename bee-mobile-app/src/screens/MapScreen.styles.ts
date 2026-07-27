import { StyleSheet } from 'react-native';

export const styles = StyleSheet.create({
    screenContainer: {
        flex: 1,
        backgroundColor: '#070D17',
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingTop: 50,
        paddingBottom: 15,
        backgroundColor: '#0E1726',
        borderBottomWidth: 1,
        borderBottomColor: '#1D2C3F',
    },
    backButton: {
        backgroundColor: '#172334',
        paddingHorizontal: 12,
        paddingVertical: 8,
        borderRadius: 8,
    },
    backButtonText: {
        color: '#3388E6',
        fontWeight: '600',
        fontSize: 14,
    },
    headerTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: '#FFFFFF',
    },
    headerPlaceholder: {
        width: 60
    },

    // Map container
    mapContainer: {
        height: 260,
        backgroundColor: '#0E1726',
        margin: 20,
        borderRadius: 16,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative',
        overflow: 'hidden',
    },
    mapPlaceholderText: {
        color: '#6A798C',
        fontSize: 16,
        fontWeight: '600',
        marginTop: 8,
    },
    mapSubtext: {
        color: '#3F4E60',
        fontSize: 12,
        marginTop: 4,
    },

    // Map pin
    mapPin1: {
        position: 'absolute',
        top: 40,
        left: 50,
        backgroundColor: '#F3B12C',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 12,
    },
    mapPin2: {
        position: 'absolute',
        bottom: 50,
        right: 60,
        backgroundColor: '#3388E6',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 12,
    },
    pinText: {
        color: '#000',
        fontWeight: 'bold',
        fontSize: 11,
    },

    // Itinerary info & list of artworks
    infoSection: {
        flex: 1,
        paddingHorizontal: 20,
    },
    sectionHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 15,
    },
    sectionTitle: {
        fontSize: 12,
        fontWeight: '700',
        color: '#6A798C',
        letterSpacing: 0.8,
    },
    badgeInfo: {
        color: '#E5A024',
        fontSize: 13,
        fontWeight: 'bold',
    },

    // Individual artwork record
    artCard: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#0E1726',
        borderRadius: 12,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        padding: 14,
        marginBottom: 12,
    },
    badgeStep: {
        width: 32,
        height: 32,
        borderRadius: 16,
        backgroundColor: '#172334',
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 14,
        borderWidth: 1,
        borderColor: '#3388E6',
    },
    badgeStepText: {
        color: '#3388E6',
        fontWeight: 'bold',
        fontSize: 14,
    },
    artInfo: {
        flex: 1,
    },
    artTitle: {
        fontSize: 15,
        fontWeight: 'bold',
        color: '#FFFFFF',
    },
    artAuthor: {
        fontSize: 13,
        color: '#6A798C',
        marginTop: 2,
    },
    artRoom: {
        fontSize: 12,
        color: '#E5A024',
        marginTop: 4,
        fontWeight: '500',
    },
    actionIcon: {
        fontSize: 18,
        color: '#6A798C',
        paddingLeft: 10,
    },
});