package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.LoadCurveResultDTO;
import com.lynkvertx.pvece.dto.StorageCalculationRequestDTO;
import com.lynkvertx.pvece.dto.StorageCalculationResultDTO;
import com.lynkvertx.pvece.service.StorageCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Energy Storage Calculation REST Controller
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
@Tag(name = "Storage Calculation", description = "Energy storage system calculation APIs")
public class StorageCalculationController {

    private final StorageCalculationService calculationService;

    @PostMapping("/calculate-storage")
    @Operation(summary = "Calculate ESS", description = "Calculate optimal energy storage system configuration for a project")
    public ResponseEntity<ApiResponse<StorageCalculationResultDTO>> calculateStorage(
            @PathVariable Long projectId,
            @RequestBody StorageCalculationRequestDTO request) {
        StorageCalculationResultDTO result = calculationService.calculate(projectId, request);
        return ResponseEntity.ok(ApiResponse.success("Storage calculation completed", result));
    }

    @PostMapping("/calculate-load-curve")
    @Operation(summary = "Calculate Load Curve", description = "Calculate and return the V1G charging load curve with peak power")
    public ResponseEntity<ApiResponse<LoadCurveResultDTO>> calculateLoadCurve(
            @PathVariable Long projectId) {
        LoadCurveResultDTO result = calculationService.calculateLoadCurveForProject(projectId);
        return ResponseEntity.ok(ApiResponse.success("Load curve calculation completed", result));
    }
}
