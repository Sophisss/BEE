import React, { useState } from 'react';
import {StatusBar, View} from 'react-native';
import { TourProvider } from './src/context/TourContext';
import { ProfileScreen } from './src/screens/ProfileScreen';
import { MapScreen } from './src/screens/MapScreen';

export default function App() {
  const [currentScreen, setCurrentScreen] = useState<'profile' | 'map'>('profile');

  return (
      <TourProvider>
        <View style={{ flex: 1, backgroundColor: '#090D16' }}>
          <StatusBar barStyle="light-content" />
          {currentScreen === 'profile' ? (
              <ProfileScreen onNavigateToMap={() => setCurrentScreen('map')} />
          ) : (
              <MapScreen onBackToConfig={() => setCurrentScreen('profile')} />
          )}
        </View>
      </TourProvider>
  );
}