import React, { createContext, useContext, useState, ReactNode } from 'react';

// Tour configuration interface
export interface TourConfig {
    tourType: string;
    timeAvailable: number;
    needsAccessibility: boolean;
    preferences: string[];
    profileTarget?: string;
    language: string;
}

// Context type definition
interface TourContextType {
    tourConfig: TourConfig | null;
    updateTourConfig: (config: TourConfig) => void;
    clearTourConfig: () => void;
}

// Create Context
const TourContext = createContext<TourContextType | undefined>(undefined);

// Provider Component
export const TourProvider = ({ children }: { children: ReactNode }) => {
    const [tourConfig, setTourConfig] = useState<TourConfig | null>(null);

    const updateTourConfig = (config: TourConfig) => {
        setTourConfig(config);
    };

    const clearTourConfig = () => {
        setTourConfig(null);
    };

    return (
        <TourContext.Provider value={{ tourConfig, updateTourConfig, clearTourConfig }}>
    {children}
    </TourContext.Provider>
);
};

// Custom Hook to consume the Context easily in any component
export const useTour = () => {
    const context = useContext(TourContext);
    if (!context) {
        throw new Error('useTour must be used within a TourProvider');
    }
    return context;
};