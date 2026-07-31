import React, {useMemo} from 'react';
import {View, Platform, StyleSheet} from 'react-native';
import {WebView} from 'react-native-webview';
import {Participant} from '../types/navigation';
import {EnvironmentData, Place} from '../types/environment';

/**
 * Extended Place model containing polygon boundary coordinates.
 */
interface ExtendedPlace extends Place {
    coordinates: [number, number][]; // [[longitude, latitude], ...]
}

/**
 * Props definition for the LeafletMapView component.
 */
interface LeafletMapViewProps {
    environment: EnvironmentData;
    participants: Participant[];
    activeParticipantId: string;
}

/**
 * Utility: Calculates the center coordinate (centroid) for a place polygon.
 * Places without coordinates are simply skipped.
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
 * Utility: Generates HTML string incorporating Leaflet.js map, polygons, edges, and participant markers.
 */
const generateLeafletHTML = (
    environment: EnvironmentData,
    participants: Participant[],
    activeParticipantId: string,
    placeCentersById: Map<string, { latitude: number; longitude: number }>
): string => {
    // 1. Prepare Place Polygons Data
    const polygonsData = environment.places
        .filter((p) => (p as ExtendedPlace).coordinates?.length > 0)
        .map((p) => ({
            id: p.id,
            coords: (p as ExtendedPlace).coordinates.map(([lng, lat]) => [lat, lng]),
        }));

    // 2. Prepare Connection Lines (Edges) Data
    const linesData = environment.edges
        .map((e) => {
            const c1 = placeCentersById.get(e.source);
            const c2 = placeCentersById.get(e.target);
            return c1 && c2 ? [[c1.latitude, c1.longitude], [c2.latitude, c2.longitude]] : null;
        })
        .filter(Boolean);

    // 3. Count Occupants per Place for Horizontal Marker Offsetting
    const placeOccupantsCount: Record<string, number> = {};
    const placeOccupantsIndex: Record<string, number> = {};

    participants.forEach((p) => {
        if (!p.currentPlaceId || p.currentPlaceId === 'Unknown') return;
        placeOccupantsCount[p.currentPlaceId] = (placeOccupantsCount[p.currentPlaceId] || 0) + 1;
    });

    const participantsData = participants
        .map((p) => {
            if (!p.currentPlaceId || p.currentPlaceId === 'Unknown') return null;
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
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                if (!window.mapInstance) {
                    window.mapInstance = L.map('map', { zoomControl: true });
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(window.mapInstance);
                }
                var map = window.mapInstance;
                var polygons = ${JSON.stringify(polygonsData)};
                var lines = ${JSON.stringify(linesData)};
                var participantsList = ${JSON.stringify(participantsData)};

                if (window.mapLayers) {
                    window.mapLayers.forEach(function(l) { map.removeLayer(l); });
                }
                window.mapLayers = [];

                lines.forEach(function(l) {
                    window.mapLayers.push(L.polyline(l, { color: '#eab308', weight: 4, opacity: 0.95 }).addTo(map));
                });

                var boundsGroup = L.featureGroup();
                polygons.forEach(function(p) {
                    var poly = L.polygon(p.coords, { color: '#dc2626', fillColor: '#d1d5db', fillOpacity: 0.25, weight: 2 }).addTo(map);
                    boundsGroup.addLayer(poly);
                    window.mapLayers.push(poly);
                });

                participantsList.forEach(function(p) {
                    var customIcon = L.divIcon({ className: 'user-location-icon' + (p.isSelected ? ' active' : ''), iconSize: [12, 12] });
                    var marker = L.marker(p.center, { icon: customIcon }).addTo(map);
                    marker.bindTooltip(p.name, { permanent: false, direction: 'right', offset: [10, 0], className: 'participant-label' });
                    window.mapLayers.push(marker);
                });

                if (!window.hasCenteredMap && polygons.length > 0) {
                    map.fitBounds(boundsGroup.getBounds(), { padding: [10, 10], maxZoom: 19 });
                    window.hasCenteredMap = true;
                }
            </script>
        </body>
        </html>
    `;
};

/**
 * Interactive Leaflet Map component supporting both Web (iframe) and Native (WebView) platforms.
 */
export const LeafletMapView: React.FC<LeafletMapViewProps> = ({
                                                                  environment,
                                                                  participants,
                                                                  activeParticipantId,
                                                              }) => {

    const placeCentersById = useMemo(() => {
        return calculatePlaceCentroids(environment.places);
    }, [environment.places]);

    const leafletHTML = useMemo(() => {
        return generateLeafletHTML(environment, participants, activeParticipantId, placeCentersById);
    }, [environment, participants, activeParticipantId, placeCentersById]);

    return (
        <View style={styles.mapContainer}>
            {Platform.OS === 'web' ? (
                <iframe
                    title="Leaflet Map"
                    width="100%"
                    height="100%"
                    style={{border: 0}}
                    srcDoc={leafletHTML}
                />
            ) : (
                <WebView
                    originWhitelist={['*']}
                    source={{html: leafletHTML}}
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