package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.PvSystemConfigDTO;
import com.lynkvertx.pvece.service.PvSystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * PV System Configuration REST Controller
 */
@RestController
@RequestMapping("/api/projects/{projectId}/pv-config")
@RequiredArgsConstructor
@Tag(name = "PV System Config", description = "Photovoltaic system configuration APIs")
public class PvSystemConfigController {

    private final PvSystemConfigService pvConfigService;

    @GetMapping
    @Operation(summary = "Get PV config", description = "Get PV system configuration for a project")
    public ResponseEntity<ApiResponse<PvSystemConfigDTO>> getPvConfig(@PathVariable Long projectId) {
        return pvConfigService.getByProjectId(projectId)
            .map(config -> ResponseEntity.ok(ApiResponse.success(config)))
            .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }

    @PostMapping
    @Operation(summary = "Save PV config", description = "Save or update PV system configuration for a project")
    public ResponseEntity<ApiResponse<PvSystemConfigDTO>> savePvConfig(
            @PathVariable Long projectId,
            @RequestBody PvSystemConfigDTO dto) {
        PvSystemConfigDTO saved = pvConfigService.saveConfig(projectId, dto);
        return ResponseEntity.ok(ApiResponse.success("PV config saved successfully", saved));
    }
}
