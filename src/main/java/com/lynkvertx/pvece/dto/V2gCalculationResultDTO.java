package com.lynkvertx.pvece.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lynkvertx.pvece.dto.StorageCalculationResultDTO.LoadCurvePoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Result DTO for V2G calculation.
 * Contains pile suggestions, load curves with charge+discharge, and arbitrage revenue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class V2gCalculationResultDTO {

    /** Suggested pile counts */
    private Integer suggestedFastChargers;
    private Integer suggestedSlowChargers;
    private Integer suggestedUltraFastChargers;

    /** Per-day load curves with charge + discharge data */
    private Map<String, List<LoadCurvePoint>> dailyLoadCurves;

    /** Max envelope curve across all days */
    private List<LoadCurvePoint> maxEnvelopeCurve;

    /** Peak charging power (kW) */
    private BigDecimal peakChargingPowerKw;

    /** Peak discharge power P_discharge-power-max (kW) */
    private BigDecimal peakDischargePowerKw;

    /** Maximum daily charging energy (kWh) */
    private BigDecimal dailyMaxChargingEnergyKwh;

    /** Maximum daily discharge energy (kWh) */
    private BigDecimal dailyMaxDischargeEnergyKwh;

    /** Arbitrage revenue: weekly sum (across all operating days in the week) */
    private BigDecimal weeklyArbitrageRevenue;

    /** Arbitrage revenue: yearly projection (weekly × 52) */
    private BigDecimal yearlyArbitrageRevenue;

    /** Discharge power ratio used in this calculation (e.g. 0.85 = 85%) */
    private BigDecimal dischargePowerRatio;

    /** P_all-load-max (max combined charging power across all days) */
    @JsonProperty("pAllLoadMax")
    private BigDecimal pAllLoadMax;

    /** Step-by-step calculation debug info */
    private List<String> calculationSteps;
}
