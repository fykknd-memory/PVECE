package com.lynkvertx.pvece.controller;

import com.lynkvertx.pvece.dto.ApiResponse;
import com.lynkvertx.pvece.dto.ProjectElectricityPriceDTO;
import com.lynkvertx.pvece.dto.ProjectElectricityPriceDTO.ElectricityPriceBatchDTO;
import com.lynkvertx.pvece.service.ProjectElectricityPriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-project Electricity Price REST Controller
 */
@RestController
@RequestMapping("/api/projects/{projectId}/electricity-prices")
@RequiredArgsConstructor
@Tag(name = "Electricity Prices", description = "Per-project TOU electricity price APIs")
public class ProjectElectricityPriceController {

    private final ProjectElectricityPriceService priceService;

    @GetMapping
    @Operation(summary = "Get electricity prices", description = "Get TOU electricity prices for a project")
    public ResponseEntity<ApiResponse<List<ProjectElectricityPriceDTO>>> getPrices(@PathVariable Long projectId) {
        List<ProjectElectricityPriceDTO> prices = priceService.getByProjectId(projectId);
        return ResponseEntity.ok(ApiResponse.success(prices));
    }

    @PostMapping
    @Operation(summary = "Save electricity prices", description = "Save TOU electricity prices for a project (batch)")
    public ResponseEntity<ApiResponse<List<ProjectElectricityPriceDTO>>> savePrices(
            @PathVariable Long projectId,
            @RequestBody ElectricityPriceBatchDTO batch) {
        List<ProjectElectricityPriceDTO> saved = priceService.savePrices(projectId, batch);
        return ResponseEntity.ok(ApiResponse.success("Electricity prices saved successfully", saved));
    }
}
