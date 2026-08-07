package org.unicam.intermediate.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.unicam.intermediate.models.dto.Response;
import org.unicam.intermediate.service.ProcessVariableService;

import java.util.Map;

/**
 * REST API for managing BPMN Data Objects via Camunda Process Variables.
 * Endpoints:
 *   GET  /api/process/{processInstanceId}/variables
 *   GET  /api/process/{processInstanceId}/variables/{dataObjectName}
 *   GET  /api/process/{processInstanceId}/variables/{dataObjectName}/field
 *   POST /api/process/{processInstanceId}/variables/{dataObjectName}
 *   PUT  /api/process/{processInstanceId}/variables/{dataObjectName}
 *   PUT  /api/process/{processInstanceId}/variables/{dataObjectName}/field
 *   DELETE /api/process/{processInstanceId}/variables/{dataObjectName}
 */
@RestController
@RequestMapping("/api/process/{processInstanceId}/variables")
@RequiredArgsConstructor
@Slf4j
public class ProcessVariableController {

    private final ProcessVariableService processVariableService;

    /**
     * Get all variables (data objects) for a process instance
     * GET /api/process/{processInstanceId}/variables
     */
    @GetMapping
    public ResponseEntity<Response<Map<String, Object>>> getAllVariables(
            @PathVariable String processInstanceId) {
        try {
            Map<String, Object> variables = processVariableService.getAllVariables(processInstanceId);
            return ResponseEntity.ok(Response.ok(variables));
        } catch (Exception e) {
            log.error("Failed to get all variables for process {}", processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to get variables: " + e.getMessage()));
        }
    }

    /**
     * Get a single data object by name
     * GET /api/process/{processInstanceId}/variables/{dataObjectName}
     */
    @GetMapping("/{dataObjectName}")
    public ResponseEntity<Response<Object>> getVariable(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName) {
        try {
            Object variable = processVariableService.getVariable(processInstanceId, dataObjectName);
            if (variable == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Data object not found: " + dataObjectName));
            }
            return ResponseEntity.ok(Response.ok(variable));
        } catch (Exception e) {
            log.error("Failed to get variable '{}' for process {}", dataObjectName, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to get data object: " + e.getMessage()));
        }
    }

    /**
     * Get a nested field from a data object
     * GET /api/process/{processInstanceId}/variables/{dataObjectName}/field?path=artworks_to_visit[0]
     */
    @GetMapping("/{dataObjectName}/field")
    public ResponseEntity<Response<Object>> getNestedField(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName,
            @RequestParam String path) {
        try {
            Object value = processVariableService.getNestedField(processInstanceId, dataObjectName, path);
            if (value == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Field not found: " + path));
            }
            return ResponseEntity.ok(Response.ok(value));
        } catch (Exception e) {
            log.error("Failed to get nested field '{}.{}' for process {}",
                    dataObjectName, path, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to get field: " + e.getMessage()));
        }
    }

    /**
     * Create/set a data object with a complete value
     * POST /api/process/{processInstanceId}/variables/{dataObjectName}
     * Body: { ... complete data object ... }
     */
    @PostMapping("/{dataObjectName}")
    public ResponseEntity<Response<Void>> createVariable(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName,
            @RequestBody Object value) {
        try {
            processVariableService.setVariable(processInstanceId, dataObjectName, value);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Response.ok(null));
        } catch (Exception e) {
            log.error("Failed to create variable '{}' for process {}", dataObjectName, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to create data object: " + e.getMessage()));
        }
    }

    /**
     * Update a complete data object
     * PUT /api/process/{processInstanceId}/variables/{dataObjectName}
     * Body: { ... complete updated data object ... }
     */
    @PutMapping("/{dataObjectName}")
    public ResponseEntity<Response<Void>> updateVariable(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName,
            @RequestBody Object value) {
        try {
            if (processVariableService.variableExists(processInstanceId, dataObjectName)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Data object not found: " + dataObjectName));
            }
            processVariableService.setVariable(processInstanceId, dataObjectName, value);
            return ResponseEntity.ok(Response.ok(null));
        } catch (Exception e) {
            log.error("Failed to update variable '{}' for process {}", dataObjectName, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to update data object: " + e.getMessage()));
        }
    }

    /**
     * Update a nested field in a data object
     * PUT /api/process/{processInstanceId}/variables/{dataObjectName}/field?path=available_time
     * Body: 45 (or any value)
     */
    @PutMapping("/{dataObjectName}/field")
    public ResponseEntity<Response<Void>> updateNestedField(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName,
            @RequestParam String path,
            @RequestBody Object value) {
        try {
            if (processVariableService.variableExists(processInstanceId, dataObjectName)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Data object not found: " + dataObjectName));
            }
            processVariableService.setNestedField(processInstanceId, dataObjectName, path, value);
            return ResponseEntity.ok(Response.ok(null));
        } catch (Exception e) {
            log.error("Failed to update nested field '{}.{}' for process {}",
                    dataObjectName, path, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to update field: " + e.getMessage()));
        }
    }

    /**
     * Delete a data object
     * DELETE /api/process/{processInstanceId}/variables/{dataObjectName}
     */
    @DeleteMapping("/{dataObjectName}")
    public ResponseEntity<Response<Void>> deleteVariable(
            @PathVariable String processInstanceId,
            @PathVariable String dataObjectName) {
        try {
            if (processVariableService.variableExists(processInstanceId, dataObjectName)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Response.error("Data object not found: " + dataObjectName));
            }
            processVariableService.deleteVariable(processInstanceId, dataObjectName);
            return ResponseEntity.ok(Response.ok(null));
        } catch (Exception e) {
            log.error("Failed to delete variable '{}' for process {}", dataObjectName, processInstanceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Response.error("Failed to delete data object: " + e.getMessage()));
        }
    }
}

