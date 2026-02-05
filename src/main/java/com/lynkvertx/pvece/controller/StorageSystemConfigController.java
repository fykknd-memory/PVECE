package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.StorageSystemConfigDTO;
import com.lynkvertx.pvece.service.StorageSystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Storage System Configuration REST Controller
 */
@RestController
@RequestMapping("/api/projects/{projectId}/storage-config")
@RequiredArgsConstructor
@Tag(name = "Storage System Config", description = "Energy storage system configuration APIs")
public class StorageSystemConfigController {

    private final StorageSystemConfigService storageConfigService;

    @GetMapping
    @Operation(summary = "Get storage config", description = "Get storage system configuration for a project")
    public ResponseEntity<ApiResponse<StorageSystemConfigDTO>> getStorageConfig(@PathVariable Long projectId) {
        return storageConfigService.getByProjectId(projectId)
            .map(config -> ResponseEntity.ok(ApiResponse.success(config)))
            .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }

    @PostMapping
    @Operation(summary = "Save storage config", description = "Save or update storage system configuration for a project")
    public ResponseEntity<ApiResponse<StorageSystemConfigDTO>> saveStorageConfig(
            @PathVariable Long projectId,
            @RequestBody StorageSystemConfigDTO dto) {
        StorageSystemConfigDTO saved = storageConfigService.saveConfig(projectId, dto);
        return ResponseEntity.ok(ApiResponse.success("Storage config saved successfully", saved));
    }
}
