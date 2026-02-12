package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.V2gCalculationRequestDTO;
import com.lynkvertx.pvece.dto.V2gCalculationResultDTO;
import com.lynkvertx.pvece.service.StorageCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * V2G Calculation REST Controller
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "V2G Calculation", description = "V2G charging/discharging calculation APIs")
public class V2gCalculationController {

    private final StorageCalculationService calculationService;

    @PostMapping("/api/v2g/calculate")
    @Operation(summary = "Standalone V2G calculation", description = "Calculate V2G load curves and arbitrage from request body (no project required)")
    public ResponseEntity<ApiResponse<V2gCalculationResultDTO>> calculate(
            @RequestBody V2gCalculationRequestDTO request) {
        V2gCalculationResultDTO result = calculationService.calculateV2g(request);
        return ResponseEntity.ok(ApiResponse.success("V2G calculation completed", result));
    }

    @PostMapping("/api/projects/{projectId}/calculate-v2g")
    @Operation(summary = "Project-based V2G calculation", description = "Calculate V2G load curves and arbitrage using saved project data")
    public ResponseEntity<ApiResponse<V2gCalculationResultDTO>> calculateForProject(
            @PathVariable Long projectId) {
        V2gCalculationResultDTO result = calculationService.calculateV2gForProject(projectId);
        return ResponseEntity.ok(ApiResponse.success("V2G calculation completed", result));
    }
}
