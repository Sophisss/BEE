import {Participant, PendingAction} from '../types/navigation';

/**
 * Returns the default base URL for the backend API.
 * @returns {string} Default base API URL.
 */
export function defaultBaseUrl(): string {
    return 'http://localhost:8082';
}

/**
 * Internal variable holding the current active base URL.
 */
let currentBaseUrl: string = defaultBaseUrl();

/**
 * Sets a new base URL for API requests.
 * @param {string} url - The new base URL to set.
 */
export function setBaseUrl(url: string): void {
    currentBaseUrl = url;
}

/**
 * Retrieves the currently configured base URL, stripped of any trailing slash.
 * @returns {string} Cleaned base URL.
 */
export function getBaseUrl(): string {
    return (currentBaseUrl || 'http://localhost:8082').trim().replace(/\/$/, '');
}

/**
 * Helper function to perform HTTP requests to the backend API.
 * Handles headers, JSON serialization/deserialization, and basic HTTP error responses.
 *
 * @template T - The expected return type from the API call.
 * @param {string} method - HTTP method (GET, POST, PUT, DELETE, etc.).
 * @param {string} path - Endpoint path relative to the base URL.
 * @param {any} [body] - Optional request body payload.
 * @returns {Promise<T>} Promise resolving to the parsed response JSON.
 * @throws {Error} Throws an error if the HTTP response status is not OK (2xx).
 */
async function apiCall<T>(method: string, path: string, body?: any): Promise<T> {
    const url = getBaseUrl() + path;
    console.log(`Calling Engine API (${method}):`, url);

    const opts: RequestInit = {
        method,
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
    };

    if (body !== undefined) {
        opts.body = JSON.stringify(body);
    }

    const response = await fetch(url, opts);
    const json = await response.json().catch(() => ({}));

    if (!response.ok) {
        const msg = json && json.message ? json.message : 'HTTP ' + response.status;
        throw new Error(msg);
    }

    return json;
}

/**
 * Fetches the list of active physical places (PP).
 * Endpoint: GET /api/environment/pp
 */
export async function fetchPhysicalPlacesApi(): Promise<any[]> {
    const res = await apiCall<{ data?: any[] }>('GET', '/api/environment/pp');
    return (res && res.data) || (Array.isArray(res) ? res : []);
}

/**
 * Fetches the list of logical places (LP).
 * Endpoint: GET /api/environment/lp
 */
export async function fetchLogicalPlacesApi(): Promise<any[]> {
    const res = await apiCall<{ data?: any[] }>('GET', '/api/environment/lp');
    return (res && res.data) || (Array.isArray(res) ? res : []);
}

/**
 * Fetches the list of edges.
 * Endpoint: GET /api/environment/edges
 */
export async function fetchEdgesApi(): Promise<any[]> {
    const res = await apiCall<{ data?: any[] }>('GET', '/api/environment/edges');
    return (res && res.data) || (Array.isArray(res) ? res : []);
}

/**
 * Fetches the list of active participants from the environment backend.
 *
 * Endpoint: GET /api/environment/participants
 *
 * @returns {Promise<Participant[]>} A list of mapped Participant objects.
 */
export async function refreshParticipantsApi(): Promise<Participant[]> {
    const res = await apiCall<{ data?: any[] }>('GET', '/api/environment/participants');
    const participants = (res && res.data) || [];

    return participants.map((p) => {
        const participantLabel = p && p.name ? p.name : p.id || 'Unknown';
        const positionText = p.position ? ` (${p.position})` : ' (null)';

        return {
            id: p.id,
            name: `${participantLabel}${positionText}`,
            rawName: p.name || p.id,
            attributes: p.attributes || {},
            currentPlaceId: p.position || 'null',
        };
    });
}

/**
 * Updates custom attributes for a specific participant.
 *
 * Endpoint: PUT /api/environment/participants/{id}/attributes
 *
 * @param {string} participantId - The unique identifier of the participant.
 * @param {Record<string, any>} attributes - Map of attribute keys and values to update.
 * @returns {Promise<any>} The server response payload.
 */
export async function updateParticipantAttributesApi(
    participantId: string,
    attributes: Record<string, any>
): Promise<any> {
    return await apiCall<any>(
        'PUT',
        `/api/environment/participants/${encodeURIComponent(participantId)}/attributes`,
        attributes
    );
}

/**
 * Updates the current physical/logical place position of a participant.
 *
 * Endpoint: PUT /api/environment/participants/{id}/position
 *
 * @param {string} participantId - The unique identifier of the participant.
 * @param {string} placeId - The ID of the target physical/logical place.
 * @returns {Promise<any>} The server response payload containing updated position.
 */
export async function updateParticipantPositionApi(
    participantId: string,
    placeId: string
): Promise<any> {
    return await apiCall<any>(
        'PUT',
        `/api/environment/participants/${encodeURIComponent(participantId)}/position`,
        {position: placeId}
    );
}

/**
 * Fetches all pending actions assigned to a specific participant.
 *
 * Endpoint: GET /api/environment/participants/{id}/actions/pending
 *
 * @param {string} participantId - The unique identifier of the participant.
 * @returns {Promise<PendingAction[]>} A list of pending action objects.
 */
export async function refreshPendingActionsApi(participantId: string): Promise<PendingAction[]> {
    if (!participantId) return [];

    try {
        const res = await apiCall<{ data?: any[] }>(
            'GET',
            `/api/environment/participants/${encodeURIComponent(participantId)}/actions/pending`
        );
        const actions = (res && res.data) || [];

        return actions.map((action) => ({
            activityId: action.activityId || '',
            message: action.message || ('Perform action: ' + (action.action || 'unknown')),
            action: action.action || '',
        }));
    } catch (err) {
        console.warn('Failed to load pending actions:', err);
        return [];
    }
}

/**
 * Updates an attribute for a specific physical place (PP).
 *
 * Endpoint: PUT /api/environment/pps/{placeId}/attributes/{key}
 *
 * @param {string} placeId - The ID of the target physical place (e.g., 'place2').
 * @param {string} key - Attribute key (e.g., 'state').
 * @param {any} value - Value to set.
 * @returns {Promise<any>} Server response.
 */
export async function updatePlaceAttributeApi(
    placeId: string,
    key: string,
    value: any
): Promise<any> {
    let parsedValue: any = value;

    if (value === 'true') parsedValue = true;
    else if (value === 'false') parsedValue = false;
    else if (typeof value === 'string' && !isNaN(Number(value)) && value.trim() !== '') {
        parsedValue = Number(value);
    }

    return await apiCall<any>(
        'PUT',
        `/api/environment/pps/${encodeURIComponent(placeId)}/attributes/${encodeURIComponent(key)}`,
        {value: parsedValue}
    );
}