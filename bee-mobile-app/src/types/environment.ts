export interface Place {
    id: string;
    name: string;
    description?: string;
    attributes?: Record<string, any>;
    coordinates: [number, number][];
}

export interface Edge {
    source: string;
    target: string;
    distance?: number;
}

export interface EnvironmentData {
    places: Place[];
    edges: Edge[];
}