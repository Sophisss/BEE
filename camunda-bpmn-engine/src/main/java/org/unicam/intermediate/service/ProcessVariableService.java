package org.unicam.intermediate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing BPMN Data Objects and process variables via Camunda engine services.
 * <p>
 * Supports deep path traversing, nested JSON property retrieval, array index manipulations,
 * and element removals from nested collections.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessVariableService {

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;

    /**
     * Retrieves all execution variables associated with a given process instance.
     * Checks RuntimeService first; if non-existent or completed, falls back to HistoryService.
     *
     * @param processInstanceId the target process instance identifier
     * @return a map containing all resolved process variables, or an empty map if none found
     */
    public Map<String, Object> getAllVariables(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            // 1. Try reading variables at the runtime process instance level
            Map<String, Object> variables = runtimeService.getVariables(processInstanceId);
            if (variables != null && !variables.isEmpty()) {
                log.debug("Retrieved {} runtime variables for process instance {}", variables.size(), processInstanceId);
                return variables;
            }

            // 2. If runtime variables are empty or instance ended, query historic variable instances
            List<HistoricVariableInstance> historicVariables = historyService
                    .createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list();

            if (historicVariables == null || historicVariables.isEmpty()) {
                log.warn("Process instance not found in runtime or history: {}", processInstanceId);
                return Collections.emptyMap();
            }

            Map<String, Object> historicMap = historicVariables.stream()
                    .collect(Collectors.toMap(
                            HistoricVariableInstance::getName,
                            HistoricVariableInstance::getValue,
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));
            log.debug("Retrieved {} historic variables for process instance {}", historicMap.size(), processInstanceId);
            return historicMap;
        } catch (Exception e) {
            log.error("Failed to get variables for process instance {}", processInstanceId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Retrieves a single process variable value by name.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      the variable key name
     * @return variable object value, or {@code null} if not found
     */
    public Object getVariable(String processInstanceId, String variableName) {
        if (processInstanceId == null || processInstanceId.isBlank() || variableName == null) {
            return null;
        }

        if (!processInstanceExists(processInstanceId)) {
            log.warn("Process instance '{}' does not exist; refusing to read variable '{}'",
                    processInstanceId, variableName);
            return null;
        }

        try {
            // Try fetching from RuntimeService first
            Object variable = null;
            try {
                variable = runtimeService.getVariable(processInstanceId, variableName);
            } catch (Exception ignored) {
                // Fallback catch if runtime instance is no longer active
            }

            if (variable == null) {
                HistoricVariableInstance historicVariable = historyService
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .variableName(variableName)
                        .singleResult();
                variable = historicVariable != null ? historicVariable.getValue() : null;
            }

            if (variable == null) {
                log.debug("Variable '{}' not found in process instance {}", variableName, processInstanceId);
                return null;
            }
            log.debug("Retrieved variable '{}' from process instance {}", variableName, processInstanceId);
            return variable;
        } catch (Exception e) {
            log.error("Failed to get variable '{}' from process instance {}", variableName, processInstanceId, e);
            return null;
        }
    }

    /**
     * Sets or updates a root-level process variable value.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      the variable key name
     * @param value             the payload object value to persist
     */
    public void setVariable(String processInstanceId, String variableName, Object value) {
        try {
            runtimeService.setVariable(processInstanceId, variableName, value);
            log.info("Set variable '{}' in process instance {}", variableName, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to set variable '{}' in process instance {}", variableName, processInstanceId, e);
            throw new RuntimeException("Failed to set process variable: " + e.getMessage(), e);
        }
    }

    /**
     * Navigates and extracts a nested property field from a complex Data Object using dot notation and array indexes.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      root variable key
     * @param fieldPath         dot-notation path (e.g. "user.address.cities[0]")
     * @return nested value or {@code null} if path resolution fails
     */
    public Object getNestedField(String processInstanceId, String variableName, String fieldPath) {
        try {
            Object variable = getVariable(processInstanceId, variableName);
            if (variable == null) {
                return null;
            }

            JsonNode node = toCustomJsonNode(variable);
            return traversePath(node, fieldPath);
        } catch (Exception e) {
            log.error("Failed to get nested field '{}.{}' from process instance {}",
                    variableName, fieldPath, processInstanceId, e);
            return null;
        }
    }

    /**
     * Updates or creates a nested property field in a Data Object using dot notation and array indexes.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      root variable key
     * @param fieldPath         dot-notation target path (e.g. "user.address.cities[0]")
     * @param value             the new value to assign to the path
     */
    public void setNestedField(String processInstanceId, String variableName, String fieldPath, Object value) {
        try {
            Object variable = getVariable(processInstanceId, variableName);
            JsonNode initialNode = (variable != null) ? toCustomJsonNode(variable) : objectMapper.createObjectNode();

            if (!initialNode.isObject()) {
                initialNode = objectMapper.createObjectNode();
            }

            ObjectNode root = (ObjectNode) initialNode;
            setPathValue(root, fieldPath, value);

            Object updatedValue = objectMapper.treeToValue(root, Object.class);
            setVariable(processInstanceId, variableName, updatedValue);

            log.info("Set nested field '{}.{}' in process instance {}", variableName, fieldPath, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to set nested field '{}.{}' in process instance {}",
                    variableName, fieldPath, processInstanceId, e);
            throw new RuntimeException("Failed to set nested field: " + e.getMessage(), e);
        }
    }

    /**
     * Removes an element at a specific index from a nested array within a process variable.
     * Safely updates and re-persists the updated Data Object back into Camunda RuntimeService.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      root variable key
     * @param fieldPath         dot-notation path pointing to the array (e.g. "targetPlaceIds")
     * @param index             the index of the element to remove (e.g. 0)
     */
    public void removeElementFromList(String processInstanceId, String variableName, String fieldPath, int index) {
        try {
            Object variable = getVariable(processInstanceId, variableName);
            if (variable == null) {
                log.warn("Cannot remove element: root variable '{}' not found in instance {}", variableName, processInstanceId);
                return;
            }

            JsonNode initialNode = toCustomJsonNode(variable);
            if (initialNode == null || !initialNode.isObject()) {
                log.warn("Cannot remove element: variable '{}' is not a JSON Object in instance {}", variableName, processInstanceId);
                return;
            }

            ObjectNode root = (ObjectNode) initialNode;
            List<String> segments = parsePath(fieldPath);
            JsonNode current = root;

            // Traverse the tree up to the parent node of the target array field
            for (int i = 0; i < segments.size() - 1; i++) {
                String segment = segments.get(i);
                current = traverseSegment(current, segment);
                if (current == null) {
                    log.warn("Path segment '{}' in fieldPath '{}.{}' resolved to null", segment, variableName, fieldPath);
                    return;
                }
            }

            String lastSegment = segments.get(segments.size() - 1);
            JsonNode targetNode = current.get(lastSegment);

            if (targetNode != null && targetNode.isArray()) {
                ArrayNode arrayNode = (ArrayNode) targetNode;
                if (index >= 0 && index < arrayNode.size()) {
                    arrayNode.remove(index);
                    log.info("Removed element at index {} from '{}.{}' in process instance {}", index, variableName, fieldPath, processInstanceId);

                    // Re-serialize modified JSON object back to generic Map/Object format and persist
                    Object updatedValue = objectMapper.treeToValue(root, Object.class);
                    setVariable(processInstanceId, variableName, updatedValue);
                } else {
                    log.warn("Index {} out of bounds for array '{}.{}' (size: {}) in instance {}",
                            index, variableName, fieldPath, arrayNode.size(), processInstanceId);
                }
            } else {
                log.warn("Target field '{}.{}' is not a valid ArrayNode in process instance {}", variableName, fieldPath, processInstanceId);
            }
        } catch (Exception e) {
            log.error("Failed to remove element at index {} from '{}.{}' in process instance {}",
                    index, variableName, fieldPath, processInstanceId, e);
            throw new RuntimeException("Failed to remove element from list: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a process variable from the runtime engine.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      variable key to remove
     */
    public void deleteVariable(String processInstanceId, String variableName) {
        try {
            runtimeService.removeVariable(processInstanceId, variableName);
            log.info("Deleted variable '{}' from process instance {}", variableName, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to delete variable '{}' from process instance {}", variableName, processInstanceId, e);
        }
    }

    /**
     * Checks if a specific variable exists in runtime or history contexts.
     *
     * @param processInstanceId target process instance ID
     * @param variableName      the variable key name to check
     * @return {@code true} if variable exists, {@code false} otherwise
     */
    public boolean variableExists(String processInstanceId, String variableName) {
        return getVariable(processInstanceId, variableName) != null;
    }

    /**
     * Converts a raw variable object (Map, POJO, or JSON String) into a Jackson {@link JsonNode} for traversal.
     */
    private JsonNode toCustomJsonNode(Object variable) {
        if (variable == null) {
            return null;
        }
        if (variable instanceof String str) {
            try {
                String trimmed = str.trim();
                if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                    return objectMapper.readTree(trimmed);
                }
            } catch (Exception ignored) {
            }
        }
        return objectMapper.valueToTree(variable);
    }

    /**
     * Traverses a JSON node tree using dot notation and bracketed array index notation.
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

            node = traverseSegment(node, segment);
        }

        if (node == null || node.isNull()) {
            return null;
        }

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
     * Helper method to traverse a single path segment (handles property fields or bracketed index notation).
     */
    private JsonNode traverseSegment(JsonNode node, String segment) {
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
                return node.get(index);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if ("length".equals(segment)) {
            // Special case for array length
            if (node.isArray()) {
                return objectMapper.valueToTree(node.size());
            }
            return null;
        } else {
            return node.get(segment);
        }
    }

    /**
     * Dynamically constructs intermediate nodes and writes a target value into a JSON tree path.
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
            if (current instanceof ObjectNode objectNode) {
                objectNode.set(lastSegment, objectMapper.valueToTree(value));
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
     * Splits a dot-delimited path into individual segments.
     */
    private List<String> parsePath(String path) {
        if (path == null || path.isBlank()) {
            return Collections.emptyList();
        }

        List<String> segments = new ArrayList<>();
        String[] parts = path.split("\\.");

        for (String part : parts) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }

        return segments;
    }

    /**
     * Checks if a process instance exists in either the runtime or history context.
     * @param processInstanceId the process instance ID to check.
     * @return true if the process instance exists, false otherwise.
     */
    private boolean processInstanceExists(String processInstanceId) {
        try {
            return runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult() != null
                    || historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult() != null;
        } catch (Exception e) {
            log.warn("Unable to validate process instance '{}': {}", processInstanceId, e.getMessage());
            return false;
        }
    }
}