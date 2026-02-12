package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result DTO for energy storage calculation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageCalculationResultDTO {

    /** ESS rated power in kW (actual = units × model power) */
    private BigDecimal essRatedPowerKw;

    /** ESS capacity in kWh (actual = units × model capacity) */
    private BigDecimal essCapacityKwh;

    /** Raw calculated ESS power before rounding to standard model (kW) */
    private BigDecimal essCalculatedPowerKw;

    /** Raw calculated ESS capacity before rounding to standard model (kWh) */
    private BigDecimal essCalculatedCapacityKwh;

    /** Standard ESS model unit power (kW) */
    private BigDecimal essModelPowerKw;

    /** Standard ESS model unit capacity (kWh) */
    private BigDecimal essModelCapacityKwh;

    /** Number of standard ESS units needed */
    private Integer essUnits;

    /** Peak power of the total load curve in kW */
    private BigDecimal loadPeakPowerKw;

    /** Peak power of PV output in kW */
    private BigDecimal pvPeakPowerKw;

    /** Transformer capacity in kVA */
    private BigDecimal transformerCapacityKva;

    /** Whether the transformer capacity was auto-calculated */
    private boolean transformerAutoCalculated;

    /** Warning message (e.g. ESS exceeds transformer capacity) */
    private String warning;

    /** 15-minute load curve data */
    private List<LoadCurvePoint> loadCurve;

    /** 1-20 year economic indicators */
    private List<YearlyEconomicIndicator> yearlyEconomics;

    /** Step-by-step calculation debug info */
    private List<String> calculationSteps;

    @Data
    @NoArgsConstructor
    public static class LoadCurvePoint {
        private String timeSlot;    // HH:mm
        private BigDecimal powerKw;            // Rated instantaneous power when active (kW)
        private BigDecimal dischargePowerKw;   // Rated discharge power (negative, kW)
        private BigDecimal energyKwh;          // Actual charging energy this slot (kWh, null = powerKw × interval)
        private BigDecimal dischargeEnergyKwh; // Actual discharge energy this slot (negative kWh, null = dischargePowerKw × interval)

        public LoadCurvePoint(String timeSlot, BigDecimal powerKw) {
            this.timeSlot = timeSlot;
            this.powerKw = powerKw;
            this.dischargePowerKw = BigDecimal.ZERO;
        }

        public LoadCurvePoint(String timeSlot, BigDecimal powerKw, BigDecimal dischargePowerKw) {
            this.timeSlot = timeSlot;
            this.powerKw = powerKw;
            this.dischargePowerKw = dischargePowerKw != null ? dischargePowerKw : BigDecimal.ZERO;
        }

        public LoadCurvePoint(String timeSlot, BigDecimal powerKw, BigDecimal dischargePowerKw,
                              BigDecimal energyKwh, BigDecimal dischargeEnergyKwh) {
            this.timeSlot = timeSlot;
            this.powerKw = powerKw;
            this.dischargePowerKw = dischargePowerKw != null ? dischargePowerKw : BigDecimal.ZERO;
            this.energyKwh = energyKwh;
            this.dischargeEnergyKwh = dischargeEnergyKwh;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class YearlyEconomicIndicator {
        private int year;
        private BigDecimal arbitrageRevenue;
        private BigDecimal peakShavingRevenue;
        private BigDecimal operatingCost;
        private BigDecimal netProfit;
        private BigDecimal cumulativeProfit;
    }
}
