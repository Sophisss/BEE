export interface PendingAction {
    id?: string;
    action?: string;
    message?: string;
    activityId?: string;
}

export interface Participant {
    id: string;
    name: string;
    attributes: Record<string, any>;
    currentPlaceId: string;
}

export type RootStackParamList = {
    ParticipantSetup: undefined;
    Map: {
        participants: Participant[];
    };
};