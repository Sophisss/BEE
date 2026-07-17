package org.unicam.intermediate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Execution;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for managing BPMN Data Objects via Camunda Process Variables.
 * Data Objects from BPMN are stored as process variables. This service
 * provides a generic API to read/write them, including nested field access.
 * Usage:
 *   - Get: getVariable(processInstanceId, "Tour_settings")
 *   - Set: setVariable(processInstanceId, "Tour_settings", {...})
 *   - Get nested: getNestedField(processInstanceId, "Tour_settings", "artworks_to_visit[0]")
 *   - Set nested: setNestedField(processInstanceId, "Tour_settings", "available_time", 45)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessVariableService {

    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    /**
     * Get all variables for a process instance
     */
    public Map<String, Object> getAllVariables(String processInstanceId) {
        try {
            Execution execution = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();

            if (execution == null) {
                log.warn("Process instance not found: {}", processInstanceId);
                return Collections.emptyMap();
            }

            Map<String, Object> variables = runtimeService.getVariables(execution.getId());
            log.debug("Retrieved {} variables for process instance {}", variables.size(), processInstanceId);
            return variables;
        } catch (Exception e) {
            log.error("Failed to get variables for process instance {}", processInstanceId, e);
            throw new RuntimeException("Failed to get process variables: " + e.getMessage(), e);
        }
    }

    /**
     * Get a single variable (data object) by name
     */
    public Object getVariable(String processInstanceId, String variableName) {
        try {
            Object variable = runtimeService.getVariable(processInstanceId, variableName);
            if (variable == null) {
                log.debug("Variable not found: {} in process {}", variableName, processInstanceId);
                return null;
            }
            log.debug("Retrieved variable '{}' from process {}", variableName, processInstanceId);
            return variable;
        } catch (Exception e) {
            log.error("Failed to get variable '{}' from process {}", variableName, processInstanceId, e);
            throw new RuntimeException("Failed to get process variable: " + e.getMessage(), e);
        }
    }

    /**
     * Set a variable (data object) with a complete value
     */
    public void setVariable(String processInstanceId, String variableName, Object value) {
        try {
            runtimeService.setVariable(processInstanceId, variableName, value);
            log.info("Set variable '{}' in process {}", variableName, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to set variable '{}' in process {}", variableName, processInstanceId, e);
            throw new RuntimeException("Failed to set process variable: " + e.getMessage(), e);
        }
    }

    /**
     * Get a nested field from a data object using dot notation and array indices
     * Examples:
     *   - "guided" → boolean field
     *   - "artworks_to_visit[0]" → first element of array
     *   - "artworks_to_visit.length" → array length
     */
    public Object getNestedField(String processInstanceId, String variableName, String fieldPath) {
        try {
            Object variable = getVariable(processInstanceId, variableName);
            if (variable == null) {
                return null;
            }

            JsonNode node = objectMapper.valueToTree(variable);
            return traversePath(node, fieldPath);
        } catch (Exception e) {
            log.error("Failed to get nested field '{}.{}' from process {}",
                    variableName, fieldPath, processInstanceId, e);
            throw new RuntimeException("Failed to get nested field: " + e.getMessage(), e);
        }
    }

    /**
     * Set a nested field in a data object using dot notation and array indices
     * Examples:
     *   - setNestedField(pid, "Tour_settings", "guided", false)
     *   - setNestedField(pid, "Tour_settings", "available_time", 45)
     *   - setNestedField(pid, "Tour_settings", "artworks_to_visit[0]", "NewArtwork")
     */
    public void setNestedField(String processInstanceId, String variableName, String fieldPath, Object value) {
        try {
            Object variable = getVariable(processInstanceId, variableName);
            if (variable == null) {
                throw new RuntimeException("Variable '" + variableName + "' not found");
            }

            ObjectNode node = objectMapper.valueToTree(variable);
            setPathValue(node, fieldPath, value);

            // Update the variable with modified value
            Object updatedValue = objectMapper.treeToValue(node, Object.class);
            setVariable(processInstanceId, variableName, updatedValue);

            log.info("Set nested field '{}.{}' in process {}", variableName, fieldPath, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to set nested field '{}.{}' in process {}",
                    variableName, fieldPath, processInstanceId, e);
            throw new RuntimeException("Failed to set nested field: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a variable
     */
    public void deleteVariable(String processInstanceId, String variableName) {
        try {
            runtimeService.removeVariable(processInstanceId, variableName);
            log.info("Deleted variable '{}' from process {}", variableName, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to delete variable '{}' from process {}", variableName, processInstanceId, e);
            throw new RuntimeException("Failed to delete process variable: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a variable exists
     */
    public boolean variableExists(String processInstanceId, String variableName) {
        try {
            Object var = runtimeService.getVariable(processInstanceId, variableName);
            return var != null;
        } catch (Exception e) {
            return false;
        }
    }

    // Helper methods for path traversal

    /**
     * Traverse a JSON path and return the value
     * Supports dot notation and array indices: "field.subfield[0].property"
     */
    private Object traversePath(JsonNode node, String path) {
        if (path == null || path.isEmpty() || node == null) {
            return null;
        }

        // Parse the path into segments
        List<String> segments = parsePath(path);

        for (String segment : segments) {
            if (node == null || node.isNull()) {
                return null;
            }

            if (segment.contains("[")) {
                // Handle array access: "field[index]"
                String fieldName = segment.substring(0, segment.indexOf('['));
                String indexStr = segment.substring(segment.indexOf('[') + 1, segment.indexOf(']'));

                node = node.get(fieldName);
                if (node == null || !node.isArray()) {
                    return null;
                }

                try {
                    int index = Integer.parseInt(indexStr);
                    node = node.get(index);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if ("length".equals(segment)) {
                // Special case for array length
                if (node.isArray()) {
                    return node.size();
                }
            } else {
                node = node.get(segment);
            }
        }

        if (node == null || node.isNull()) {
            return null;
        }

        // Convert JsonNode to Object based on type
        try {
            if (node.isObject()) {
                return objectMapper.treeToValue(node, Map.class);
            } else if (node.isArray()) {
                return objectMapper.treeToValue(node, List.class);
            } else if (node.isTextual()) {
                return node.textValue();
            } else if (node.isNumber()) {
                return node.numberValue();
            } else if (node.isBoolean()) {
                return node.booleanValue();
            } else {
                return objectMapper.treeToValue(node, Object.class);
            }
        } catch (Exception e) {
            return node;
        }
    }

    /**
     * Set a value at a JSON path, creating intermediate objects if needed
     */
    private void setPathValue(ObjectNode root, String path, Object value) {
        if (path == null || path.isEmpty()) {
            throw new RuntimeException("Invalid path");
        }

        List<String> segments = parsePath(path);
        JsonNode current = root;

        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            current = ensureAndGetNode(current, segment);
        }

        String lastSegment = segments.get(segments.size() - 1);

        if (lastSegment.contains("[")) {
            String fieldName = lastSegment.substring(0, lastSegment.indexOf('['));
            int index = Integer.parseInt(lastSegment.substring(lastSegment.indexOf('[') + 1, lastSegment.indexOf(']')));

            if (!current.has(fieldName) || !current.get(fieldName).isArray()) {
                ((ObjectNode) current).putArray(fieldName);
            }

            ArrayNode arrayNode = (ArrayNode) current.get(fieldName);
            while (arrayNode.size() <= index) {
                arrayNode.add(objectMapper.createObjectNode());
            }
            arrayNode.set(index, objectMapper.valueToTree(value));
        } else {
            if (current instanceof ObjectNode) {
                ((ObjectNode) current).set(lastSegment, objectMapper.valueToTree(value));
            } else {
                throw new RuntimeException("Cannot set property '" + lastSegment + "' on a non-object node");
            }
        }
    }

    private JsonNode ensureAndGetNode(JsonNode current, String segment) {
        if (segment.contains("[")) {
            String fieldName = segment.substring(0, segment.indexOf('['));
            int index = Integer.parseInt(segment.substring(segment.indexOf('[') + 1, segment.indexOf(']')));

            if (!current.has(fieldName) || !current.get(fieldName).isArray()) {
                ((ObjectNode) current).putArray(fieldName);
            }

            ArrayNode arrayNode = (ArrayNode) current.get(fieldName);
            while (arrayNode.size() <= index) {
                arrayNode.add(objectMapper.createObjectNode());
            }

            JsonNode element = arrayNode.get(index);
            if (element.isNull() || !element.isObject()) {
                element = objectMapper.createObjectNode();
                arrayNode.set(index, element);
            }
            return element;
        } else {
            if (!current.has(segment) || !current.get(segment).isObject()) {
                ((ObjectNode) current).putObject(segment);
            }
            return current.get(segment);
        }
    }

    /**
     * Parse a path string into segments
     * Examples:
     *   "field" → ["field"]
     *   "field.subfield" → ["field", "subfield"]
     *   "field[0]" → ["field[0]"]
     *   "field[0].sub" → ["field[0]", "sub"]
     */
    private List<String> parsePath(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);

            if (c == '.') {
                if (!current.isEmpty()) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
            } else if (c == '[') {
                if (!current.isEmpty()) {
                    current.append('[');
                }
                // Find matching
                int closeIdx = path.indexOf(']', i);
                if (closeIdx != -1) {
                    current.append(path, i + 1, closeIdx);
                    current.append(']');
                    i = closeIdx;
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            segments.add(current.toString());
        }

        return segments;
    }
}