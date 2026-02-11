package com.lynkvertx.pvece.dto;

import com.lynkvertx.pvece.dto.StorageCalculationResultDTO.LoadCurvePoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Result DTO for standalone load curve calculation.
 * Contains the 96-point load curve, peak power, daily energy, and debug steps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadCurveResultDTO {

    /** 15-minute load curve data (96 points) — max envelope across all days */
    private List<LoadCurvePoint> loadCurve;

    /** Per-day load curves (key = day name e.g. "周一", value = 96 LoadCurvePoints) */
    private Map<String, List<LoadCurvePoint>> dailyLoadCurves;

    /** Peak power in the load curve (P_all-load-max) in kW */
    private BigDecimal peakPowerKw;

    /** Maximum daily energy consumption across all days in kWh */
    private BigDecimal dailyEnergyKwh;

    /** Step-by-step calculation debug info */
    private List<String> calculationSteps;
}
