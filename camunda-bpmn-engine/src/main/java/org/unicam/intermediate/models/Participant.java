package org.unicam.intermediate.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Participant {
    private String id;
    private String role;
    private String displayName;
    private String processDefinitionKey;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    public Participant(String id, String role, String displayName, String processDefinitionKey) {
        this.id = id;
        this.role = role;
        this.displayName = displayName;
        this.processDefinitionKey = processDefinitionKey;
        this.attributes = new HashMap<>();
    }

    public String getLogDisplayName() {
        return displayName != null ? displayName : (role != null ? role : id);
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", getLogDisplayName(), id);
    }
}