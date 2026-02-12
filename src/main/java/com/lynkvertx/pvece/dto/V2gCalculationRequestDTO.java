package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for standalone V2G calculation.
 * Contains all data needed without requiring a saved project.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class V2gCalculationRequestDTO {

    private Integer vehicleCount;
    private BigDecimal batteryCapacityKwh;

    private Boolean enableTimeControl;
    private List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule;
    private List<V2gVehicleConfigDTO.SpecialDateEntry> specialDates;

    /** Total pile counts */
    private Integer fastChargers;
    private Integer slowChargers;
    private Integer ultraFastChargers;

    /** V2G-enabled pile counts (subset of total) */
    private Integer fastChargersV2g;
    private Integer slowChargersV2g;
    private Integer ultraFastChargersV2g;

    /** V2G discharge power ratio (fraction of charging power used for discharge, e.g. 0.85 = 85%). Null = use config default. */
    private BigDecimal dischargePowerRatio;

    /** TOU prices for arbitrage calculation */
    private List<TouPriceEntry> touPrices;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TouPriceEntry {
        private String periodType;
        private List<TimeRangeEntry> timeRanges;
        private BigDecimal price;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRangeEntry {
        private String start; // HH:mm
        private String end;   // HH:mm
    }
}
