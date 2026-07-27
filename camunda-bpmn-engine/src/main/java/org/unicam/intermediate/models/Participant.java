package org.unicam.intermediate.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Participant {
    private String id;
    private String role;
    private String displayName;
    private String processDefinitionKey;
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    public Participant(String id, String role, String displayName, String processDefinitionKey) {
        this.id = id;
        this.role = role;
        this.displayName = displayName;
        this.processDefinitionKey = processDefinitionKey;
        this.attributes = new ConcurrentHashMap<>();
    }

    public String getLogDisplayName() {
        return displayName != null ? displayName : (role != null ? role : id);
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", getLogDisplayName(), id);
    }
}