package com.lynkvertx.pvece.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * V2G Vehicle Configuration Data Transfer Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class V2gVehicleConfigDTO {

    private Long id;
    private Long projectId;
    private Integer vehicleCount;
    private BigDecimal batteryCapacityKwh;
    private Boolean enableTimeControl;
    private List<WeeklyScheduleEntry> weeklySchedule;
    private List<SpecialDateEntry> specialDates;

    /** Charging pile counts */
    private Integer fastChargers;
    private Integer slowChargers;
    private Integer ultraFastChargers;

    /**
     * A chargeable time range within a day (HH:mm start/end)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRange {
        private String start; // HH:mm
        private String end;   // HH:mm
        /** Minimum SOC (%) required by end of this range, before vehicle departure */
        private Integer minSoc;
    }

    /**
     * Weekly schedule entry for one day of the week.
     * Supports multiple chargeable time ranges per day.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeeklyScheduleEntry {
        private String day;
        @JsonProperty("isOperating")
        private boolean isOperating;
        private List<TimeRange> chargeableRanges;
        private int departureCount;
    }

    /**
     * Special date scheduling entry.
     * Supports multiple chargeable time ranges.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpecialDateEntry {
        private String date;          // yyyy-MM-dd
        private List<TimeRange> chargeableRanges;
        private int departureCount;
    }
}
