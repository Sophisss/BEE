import React from 'react';
import { View, Text, Image, StyleSheet } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RootStackParamList } from './src/types/navigation';
import { ParticipantSetupScreen } from './src/screens/ParticipantSetupScreen';
import { MapScreen } from './src/screens/MapScreen';

const Stack = createNativeStackNavigator<RootStackParamList>();

const HeaderTitleWithLogo = () => {
    return (
        <View style={styles.headerContainer}>
            <Image
                source={require('./assets/BEE.png')}
                style={styles.logo}
                resizeMode="contain"
            />
            <Text style={styles.headerTitleText}>BEE Mobile App</Text>
        </View>
    );
};

export default function App() {
    return (
        <NavigationContainer>
            <Stack.Navigator
                initialRouteName="ParticipantSetup"
                screenOptions={{
                    title: 'BEE Mobile App',
                    headerTitle: () => <HeaderTitleWithLogo />,
                    headerStyle: { backgroundColor: '#0F172A' },
                    headerTintColor: '#FFFFFF',
                }}
            >
                <Stack.Screen
                    name="ParticipantSetup"
                    component={ParticipantSetupScreen}
                />
                <Stack.Screen
                    name="Map"
                    component={MapScreen}
                />
            </Stack.Navigator>
        </NavigationContainer>
    );
}

const styles = StyleSheet.create({
    headerContainer: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    logo: {
        width: 32,
        height: 32,
        borderRadius: 8,
        marginRight: 10,
    },
    headerTitleText: {
        fontSize: 18,
        fontWeight: 'bold',
        color: '#FFFFFF',
    },
});