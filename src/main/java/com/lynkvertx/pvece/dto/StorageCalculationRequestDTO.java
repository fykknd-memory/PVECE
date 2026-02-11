package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for energy storage calculation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageCalculationRequestDTO {

    /** Optimization type: "economic" or "absorption" */
    private String optimizationType = "economic";

    /** Annual capacity decay percentage (e.g. 2 means 2%) */
    private BigDecimal annualDecayPercent = new BigDecimal("2");

    /** Whether to respond to peak shaving */
    private boolean enablePeakShaving;

    /** Number of peak shaving responses per day */
    private Integer peakShavingTimes;

    /** Peak shaving start time (HH:mm) */
    private String peakShavingStart;

    /** Peak shaving end time (HH:mm) */
    private String peakShavingEnd;

    /** Peak shaving subsidy price (yuan/kWh) */
    private BigDecimal peakShavingSubsidy;

    /** Charge mode: "one" (1 charge 1 discharge) or "two" (2 charges 2 discharges) */
    private String chargeMode = "one";
}
