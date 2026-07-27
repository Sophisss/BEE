import { StyleSheet } from 'react-native';

export const styles = StyleSheet.create({
    screenContainer: {
        flex: 1,
        backgroundColor: '#070D17'
    },
    contentContainer: {
        padding: 20,
        paddingBottom: 40
    },

    // Header Styles
    headerBox: {
        marginBottom: 15,
        marginTop: 10,
        alignItems: 'center'
    },
    welcomeTitle: {
        fontSize: 22,
        fontWeight: 'bold',
        color: '#FFFFFF',
        textAlign: 'center'
    },
    welcomeSubtitle: {
        fontSize: 13,
        color: '#6A798C',
        marginTop: 4,
        textAlign: 'center'
    },

    sectionTitle: {
        fontSize: 11,
        fontWeight: '700',
        color: '#6A798C',
        marginTop: 20,
        marginBottom: 8,
        letterSpacing: 0.8
    },

    // Segmented Control (Guided / Autonomous)
    segmentedControl: {
        flexDirection: 'row',
        backgroundColor: '#0E1726',
        borderRadius: 10,
        padding: 4,
        borderWidth: 1,
        borderColor: '#172334'
    },
    segmentButton: {
        flex: 1,
        paddingVertical: 12,
        borderRadius: 8,
        alignItems: 'center'
    },
    segmentActive: {
        backgroundColor: '#3388E6'
    },
    segmentText: {
        color: '#7E8B9B',
        fontWeight: '600',
        fontSize: 14
    },
    segmentTextActive: {
        color: '#FFFFFF',
        fontWeight: 'bold'
    },

    // Dropdown Custom Box Style
    dropdownBox: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        backgroundColor: '#0E1726',
        borderRadius: 10,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        paddingHorizontal: 14,
        paddingVertical: 14
    },
    dropdownLeft: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10
    },
    dropdownIcon: {
        color: '#3388E6',
        fontSize: 15
    },
    dropdownText: {
        color: '#FFFFFF',
        fontSize: 15,
        fontWeight: '600'
    },
    arrowIcon: {
        color: '#7E8B9B',
        fontSize: 14,
        fontWeight: 'bold'
    },

    // Chips (Preferences)
    chipsContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 10
    },
    chip: {
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderRadius: 20,
        backgroundColor: '#0E1726',
        borderWidth: 1,
        borderColor: '#1D2C3F'
    },
    chipActive: {
        borderColor: '#E5A024',
        backgroundColor: '#181A1F'
    },
    chipText: {
        color: '#7E8B9B',
        fontSize: 14,
        fontWeight: '500'
    },
    chipTextActive: {
        color: '#E5A024',
        fontWeight: 'bold'
    },

    // Switch Row
    toggleRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        backgroundColor: '#0E1726',
        borderRadius: 10,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        paddingHorizontal: 14,
        paddingVertical: 10,
        marginTop: 18
    },
    toggleLabelGroup: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10
    },
    toggleIcon: {
        fontSize: 15,
        color: '#7E8B9B'
    },
    toggleText: {
        color: '#FFFFFF',
        fontSize: 15,
        fontWeight: '500'
    },

    // Time Slider Container
    timeBox: {
        backgroundColor: '#0E1726',
        borderRadius: 10,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        padding: 16,
        marginTop: 15
    },
    timeHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        marginBottom: 8
    },
    timeLabel: {
        color: '#7E8B9B',
        fontSize: 14,
        fontWeight: '500'
    },
    timeValue: {
        color: '#E5A024',
        fontSize: 15,
        fontWeight: 'bold'
    },
    nativeSlider: {
        width: '100%',
        height: 40,
    },

    // Submit Button
    startButton: {
        backgroundColor: '#F3B12C',
        borderRadius: 10,
        paddingVertical: 16,
        alignItems: 'center',
        marginTop: 35
    },
    startButtonText: {
        color: '#000000',
        fontSize: 15,
        fontWeight: 'bold',
        letterSpacing: 0.8
    },

    // Modal Dropdown Styles
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 20
    },
    modalContent: {
        width: '100%',
        backgroundColor: '#0E1726',
        borderRadius: 12,
        borderWidth: 1,
        borderColor: '#1D2C3F',
        padding: 20,
        maxHeight: 300
    },
    modalTitle: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#FFFFFF',
        marginBottom: 15,
        textAlign: 'center'
    },
    modalOption: {
        paddingVertical: 12,
        borderBottomWidth: 1,
        borderBottomColor: '#172334'
    },
    modalOptionText: {
        color: '#FFFFFF',
        fontSize: 15,
        textAlign: 'center'
    },
});