import { Place } from '../types/environment';

export interface TourResult {
    targetPlaceIds: string[];
    navigationPath: string[];
}

export interface TourStep {
    placeId: string;
    order: number;
    label?: string;
}

/**
 * Converts calculated tour results into the step format required by the Leaflet Map component.
 */
export const buildTourStepsForMap = (
    tourResult: TourResult,
    places: Place[]
): TourStep[] => {
    return tourResult.navigationPath.map((placeId, index) => {
        const place = places.find((p) => p.id === placeId);
        return {
            placeId,
            order: index + 1,
            label: place?.name || `Step ${index + 1}`,
        };
    });
};