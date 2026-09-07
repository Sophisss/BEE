import React, { useMemo } from 'react';
import { View, Platform, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { Participant } from '../types/navigation';
import { EnvironmentData, Place } from '../types/environment';
import { TourStep } from '../services/navigationService';

/**
 * Extended Place model containing polygon boundary coordinates and attributes.
 */
interface ExtendedPlace extends Place {
    coordinates: [number, number][]; // [[longitude, latitude], ...] or [[latitude, longitude], ...]
    attributes?: Record<string, any>;
    state?: string;
    status?: string;
    is_accessible?: boolean;
}

/**
 * Props definition for the LeafletMapView component.
 */
interface LeafletMapViewProps {
    environment: EnvironmentData;
    participants: Participant[];
    activeParticipantId: string;
    activeTourSteps?: TourStep[];
    targetPlaceIds?: string[]; // Array of target POI place IDs
}

/**
 * Utility: Calculates the center coordinate (centroid) for a place polygon.
 * Places without valid coordinates are safely skipped.
 */
const calculatePlaceCentroids = (places: Place[]) => {
    const centers = new Map<string, { latitude: number; longitude: number }>();

    places.forEach((p) => {
        const extPlace = p as ExtendedPlace;
        if (extPlace.coordinates && extPlace.coordinates.length > 0) {
            let sumLat = 0;
            let sumLng = 0;
            extPlace.coordinates.forEach(([lng, lat]) => {
                sumLat += lat;
                sumLng += lng;
            });
            centers.set(extPlace.id, {
                latitude: sumLat / extPlace.coordinates.length,
                longitude: sumLng / extPlace.coordinates.length,
            });
        }
    });

    return centers;
};

/**
 * Utility: Checks if a place is closed or physically inaccessible.
 */
const isPlaceClosed = (place: ExtendedPlace): boolean => {
    if (!place) return false;

    // Direct properties check
    if (place.state?.toLowerCase() === 'closed' || place.status?.toLowerCase() === 'closed') {
        return true;
    }
    if (place.is_accessible === false) {
        return true;
    }

    // Attributes map check
    const attrs = place.attributes || {};
    const stateAttr = String(attrs.state || '').toLowerCase();
    const statusAttr = String(attrs.status || '').toLowerCase();
    const accessibleAttr = attrs.accessible;
    const isAccessibleAttr = attrs.is_accessible;
    const hasStairs = attrs.has_stairs;

    if (stateAttr === 'closed' || statusAttr === 'closed') return true;
    if (isAccessibleAttr === false || accessibleAttr === false) return true;
    if (hasStairs === true) return true;

    return false;
};

/**
 * Utility: Generates HTML content integrating Leaflet.js map, polygons, edges, tour paths, and participant markers.
 */
const generateLeafletHTML = (
    environment: EnvironmentData,
    participants: Participant[],
    activeParticipantId: string,
    placeCentersById: Map<string, { latitude: number; longitude: number }>,
    activeTourSteps: TourStep[] = [],
    targetPlaceIds: string[] = []
): string => {
    const targetSet = new Set(targetPlaceIds);

    // 1. Prepare Place Polygons Data
    const polygonsData = environment.places
        .filter((p) => (p as ExtendedPlace).coordinates?.length > 0)
        .map((p) => {
            const extPlace = p as ExtendedPlace;
            const closed = isPlaceClosed(extPlace);
            const center = placeCentersById.get(extPlace.id);

            return {
                id: extPlace.id,
                name: extPlace.name || extPlace.id,
                coords: extPlace.coordinates.map(([lng, lat]) => [lat, lng]),
                isClosed: closed,
                center: center ? [center.latitude, center.longitude] : null,
            };
        });

    // 2. Prepare Connection Lines (Edges)
    const linesData = environment.edges
        .map((e) => {
            const c1 = placeCentersById.get(e.source);
            const c2 = placeCentersById.get(e.target);
            return c1 && c2 ? [[c1.latitude, c1.longitude], [c2.latitude, c2.longitude]] : null;
        })
        .filter(Boolean);

    // 3. Find current position index of active participant within the path
    const activeParticipant = participants.find((p) => p.id === activeParticipantId);
    const currentPlaceId = activeParticipant?.currentPlaceId;
    const activeIndex = activeTourSteps.findIndex((step) => step.placeId === currentPlaceId);

    // 4. Split tour path into history (passed steps) and remaining path
    const historySteps = activeIndex > 0 ? activeTourSteps.slice(0, activeIndex + 1) : [];
    const remainingSteps = activeIndex >= 0 ? activeTourSteps.slice(activeIndex) : activeTourSteps;

    const historyPathCoords = historySteps
        .map((step) => {
            const center = placeCentersById.get(step.placeId);
            return center ? [center.latitude, center.longitude] : null;
        })
        .filter((c): c is [number, number] => c !== null);

    const remainingPathCoords = remainingSteps
        .map((step) => {
            const center = placeCentersById.get(step.placeId);
            return center ? [center.latitude, center.longitude] : null;
        })
        .filter((c): c is [number, number] => c !== null);

    // 5. Prepare Step Markers with distinction between POIs, Transit nodes, and Visited steps
    const tourStepMarkers = activeTourSteps
        .map((step, index) => {
            const center = placeCentersById.get(step.placeId);
            if (!center) return null;

            const isVisited = activeIndex > 0 && index < activeIndex;
            const isPOI = targetSet.has(step.placeId) || step.label?.includes('★') || step.label?.toUpperCase().includes('POI') || false;

            return {
                order: step.order,
                label: step.label,
                center: [center.latitude, center.longitude],
                isVisited,
                isPOI,
            };
        })
        .filter(Boolean);

    // 6. Participants Offsetting
/*    const filteredParticipants = participants.filter((p) => {
        const directType = (p as any).type?.toLowerCase() || '';

        const extProperties = (p as any).extensionProperties || (p as any).extensionElements || (p as any).attributes || {};
        const extType = String(extProperties.type || extProperties.Type || '').toLowerCase();

        const isSensorOrActuator =
            directType === 'sensor' || directType === 'actuator' ||
            extType === 'sensor' || extType === 'actuator'

        return !isSensorOrActuator;
    });*/

    const isDeviceParticipant = (p: Participant) => {
        const type = String((p as any).type || '').toLowerCase();
        const name = String(p.name || '').toLowerCase();

        const extProps = (p as any).extensionProperties || (p as any).extensionElements || (p as any).attributes || {};
        const extType = String(extProps.type || extProps.Type || '').toLowerCase();

        return (
            type === 'sensor' || type === 'actuator' ||
            extType === 'sensor' || extType === 'actuator'
        );
    };

    const validHumanParticipants = participants.filter((p) => {
        if (isDeviceParticipant(p)) return false;

        if (!p.currentPlaceId || p.currentPlaceId === 'Unknown') return false;
        return placeCentersById.has(p.currentPlaceId);
    });

    const placeOccupantsCount: Record<string, number> = {};
    const placeOccupantsIndex: Record<string, number> = {};

    validHumanParticipants.forEach((p) => {
        placeOccupantsCount[p.currentPlaceId] = (placeOccupantsCount[p.currentPlaceId] || 0) + 1;
    });

    const participantsData = validHumanParticipants
        .map((p) => {
            const center = placeCentersById.get(p.currentPlaceId);
            if (!center) return null;

            const totalInPlace = placeOccupantsCount[p.currentPlaceId] || 1;
            const currentIndex = placeOccupantsIndex[p.currentPlaceId] || 0;
            placeOccupantsIndex[p.currentPlaceId] = currentIndex + 1;

            let lat = center.latitude;
            let lng = center.longitude;

            if (totalInPlace > 1) {
                const offsetStep = 0.000045;
                const startOffset = -((totalInPlace - 1) * offsetStep) / 2;
                lng += startOffset + currentIndex * offsetStep;
            }

            return {
                id: p.id,
                name: p.name || 'Participant',
                isSelected: p.id === activeParticipantId,
                center: [lat, lng],
            };
        })
        .filter(Boolean);

    return `
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: #f8fafc; }
                .participant-label { background: white; border: none; border-radius: 6px; box-shadow: 0 2px 8px rgba(0,0,0,0.25); padding: 4px 10px; font-family: sans-serif; font-weight: 600; font-size: 13px; color: #1e293b; }
                .user-location-icon { width: 12px; height: 12px; background-color: #38bdf8; border: 2px solid #0284c7; border-radius: 50%; box-shadow: 0 1px 4px rgba(0,0,0,0.3); }
                .user-location-icon.active { background-color: #0284c7; border: 2px solid #ffffff; box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.4); z-index: 1000 !important; }
                
                /* Standard Transit Step Icon */
                .tour-step-icon { 
                    background: #2563eb; 
                    color: white; 
                    border: 2px solid #ffffff; 
                    border-radius: 50%; 
                    width: 22px; 
                    height: 22px; 
                    display: flex; 
                    align-items: center; 
                    justify-content: center; 
                    font-size: 11px; 
                    font-weight: bold; 
                    box-shadow: 0 2px 6px rgba(0,0,0,0.3);
                }

                /* Distinct POI Destination Step Icon */
                .tour-step-icon.poi { 
                    background: #f59e0b !important; 
                    border-color: #ffffff !important; 
                    width: 28px !important; 
                    height: 28px !important; 
                    font-size: 13px !important; 
                    box-shadow: 0 0 0 4px rgba(245, 158, 11, 0.4) !important; 
                    z-index: 500 !important;
                }

                /* Visited Historical Step Icon */
                .tour-step-icon.visited { 
                    background: #64748b !important; 
                    border-color: #e2e8f0 !important; 
                    opacity: 0.8; 
                }

                .closed-icon-badge {
                    font-size: 14px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                if (!window.mapInstance) {
                    // Allow deep zooming up to level 22
                    window.mapInstance = L.map('map', { zoomControl: true, maxZoom: 22 });
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { 
                        maxZoom: 22,
                        maxNativeZoom: 19 
                    }).addTo(window.mapInstance);
                }
                const map = window.mapInstance;
                const polygons = ${JSON.stringify(polygonsData)};
                const lines = ${JSON.stringify(linesData)};
                const historyPath = ${JSON.stringify(historyPathCoords)};
                const remainingPath = ${JSON.stringify(remainingPathCoords)};
                const tourSteps = ${JSON.stringify(tourStepMarkers)};
                const participantsList = ${JSON.stringify(participantsData)};

                if (window.mapLayers) {
                    window.mapLayers.forEach(function(l) { map.removeLayer(l); });
                }
                window.mapLayers = [];

                // Base Edges
                lines.forEach(function(l) {
                    window.mapLayers.push(L.polyline(l, { color: '#eab308', weight: 4, opacity: 0.95 }).addTo(map));
                });

                // Polygons & Bounds
                const boundsGroup = L.featureGroup();
                polygons.forEach(function(p) {
                    const polyStyle = p.isClosed ? {
                        color: '#b91c1c',       
                        fillColor: '#ef4444',   
                        fillOpacity: 0.55,      
                        weight: 3,
                        dashArray: '4, 4',
                        interactive: true
                    } : {
                        color: '#dc2626',       
                        fillColor: '#d1d5db',   
                        fillOpacity: 0.25,
                        weight: 2,
                        interactive: true
                    };

                    const poly = L.polygon(p.coords, polyStyle).addTo(map);
                    boundsGroup.addLayer(poly);

                    if (p.isClosed) {
                        poly.bindPopup(
                            '<div style="text-align: center; color: #991b1b; font-family: sans-serif;">' +
                                '<strong style="font-size: 14px;">⛔ ' + p.id + '</strong><br/>' +
                                '<span style="font-size: 12px; font-weight: 600;">ROOM CLOSED</span><br/>' +
                                '<span style="font-size: 11px; color: #4b5563;">Temporarily inaccessible</span>' +
                            '</div>'
                        );

                        if (p.center) {
                            const closedMarker = L.marker(p.center, {
                                icon: L.divIcon({
                                    className: 'closed-icon-badge',
                                    html: '⛔',
                                    iconSize: [18, 18],
                                    iconAnchor: [9, 9]
                                })
                            }).addTo(map);
                            window.mapLayers.push(closedMarker);
                        }
                    } else {
                        poly.bindPopup('<b>' + p.id + '</b>');
                    }

                    window.mapLayers.push(poly);
                });

                // Render Historical (Passed) Path Line in Gray
                if (historyPath.length > 1) {
                    const historyPolyline = L.polyline(historyPath, { 
                        color: '#94a3b8', 
                        weight: 5, 
                        opacity: 0.75, 
                        dashArray: '6, 8' 
                    }).addTo(map);
                    window.mapLayers.push(historyPolyline);
                }

                // Render Remaining Path Line in Blue
                if (remainingPath.length > 1) {
                    const remainingPolyline = L.polyline(remainingPath, { 
                        color: '#2563eb', 
                        weight: 6, 
                        opacity: 0.9, 
                        dashArray: '8, 6' 
                    }).addTo(map);
                    window.mapLayers.push(remainingPolyline);
                }

                // Path Step Markers with POI and Visited styling
                tourSteps.forEach(function(s) {
                    let iconClasses = 'tour-step-icon';
                    if (s.isVisited) iconClasses += ' visited';
                    else if (s.isPOI) iconClasses += ' poi';

                    const iconSize = (s.isPOI && !s.isVisited) ? 28 : 22;
                    const iconAnchor = iconSize / 2;

                    const stepIcon = L.divIcon({
                        className: '',
                        html: '<div class="' + iconClasses + '">' + s.order + '</div>',
                        iconSize: [iconSize, iconSize],
                        iconAnchor: [iconAnchor, iconAnchor]
                    });
                    const marker = L.marker(s.center, { icon: stepIcon }).addTo(map);
                    if (s.label) {
                        marker.bindTooltip(s.label, { permanent: false, direction: 'top', className: 'participant-label' });
                    }
                    window.mapLayers.push(marker);
                });

                // Participant Markers
                participantsList.forEach(function(p) {
                    const customIcon = L.divIcon({ className: 'user-location-icon' + (p.isSelected ? ' active' : ''), iconSize: [12, 12] });
                    const marker = L.marker(p.center, { icon: customIcon }).addTo(map);
                    marker.bindTooltip(p.name, { permanent: false, direction: 'right', offset: [10, 0], className: 'participant-label' });
                    window.mapLayers.push(marker);
                });

                // Force layout recalculation and auto-zoom tightly to polygon bounds
                setTimeout(function() {
                    map.invalidateSize();
                    if (boundsGroup.getLayers().length > 0) {
                        map.fitBounds(boundsGroup.getBounds(), {
                            padding: [20, 20],
                            maxZoom: 21,
                            animate: false
                        });
                    }
                }, 150);
            </script>
        </body>
        </html>
    `;
};

/**
 * Interactive Leaflet Map component supporting Web (iframe) and Native (WebView) platforms.
 */
export const LeafletMapView: React.FC<LeafletMapViewProps> = ({
                                                                  environment,
                                                                  participants,
                                                                  activeParticipantId,
                                                                  activeTourSteps = [],
                                                                  targetPlaceIds = [],
                                                              }) => {
    const placeCentersById = useMemo(() => {
        return calculatePlaceCentroids(environment.places);
    }, [environment.places]);

    const leafletHTML = useMemo(() => {
        return generateLeafletHTML(
            environment,
            participants,
            activeParticipantId,
            placeCentersById,
            activeTourSteps,
            targetPlaceIds
        );
    }, [environment, participants, activeParticipantId, placeCentersById, activeTourSteps, targetPlaceIds]);

    return (
        <View style={styles.mapContainer}>
            {Platform.OS === 'web' ? (
                <iframe
                    title="Leaflet Map"
                    width="100%"
                    height="100%"
                    style={{ border: 0 }}
                    srcDoc={leafletHTML}
                />
            ) : (
                <WebView
                    originWhitelist={['*']}
                    source={{ html: leafletHTML }}
                    style={styles.webView}
                />
            )}
        </View>
    );
};

const styles = StyleSheet.create({
    mapContainer: {
        flex: Platform.OS === 'web' ? 2 : 1,
        height: 600,
        borderRadius: 12,
        overflow: 'hidden',
        borderWidth: 1,
        borderColor: '#cbd5e1',
        backgroundColor: '#ffffff',
    },
    webView: {
        flex: 1,
    },
});