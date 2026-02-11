package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.V2gVehicleConfigDTO;
import com.lynkvertx.pvece.service.V2gVehicleConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * V2G Vehicle Configuration REST Controller
 */
@RestController
@RequestMapping("/api/projects/{projectId}/v2g-config")
@RequiredArgsConstructor
@Tag(name = "V2G Config", description = "V2G vehicle configuration APIs")
public class V2gVehicleConfigController {

    private final V2gVehicleConfigService v2gConfigService;

    @GetMapping
    @Operation(summary = "Get V2G config", description = "Get V2G vehicle configuration for a project")
    public ResponseEntity<ApiResponse<V2gVehicleConfigDTO>> getV2gConfig(@PathVariable Long projectId) {
        return v2gConfigService.getByProjectId(projectId)
            .map(config -> ResponseEntity.ok(ApiResponse.success(config)))
            .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }

    @PostMapping
    @Operation(summary = "Save V2G config", description = "Save or update V2G vehicle configuration for a project")
    public ResponseEntity<ApiResponse<V2gVehicleConfigDTO>> saveV2gConfig(
            @PathVariable Long projectId,
            @RequestBody V2gVehicleConfigDTO dto) {
        V2gVehicleConfigDTO saved = v2gConfigService.saveConfig(projectId, dto);
        return ResponseEntity.ok(ApiResponse.success("V2G config saved successfully", saved));
    }
}
