package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Per-project Electricity Price DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectElectricityPriceDTO {

    private Long id;
    private Long projectId;
    private String country;
    private String province;
    private String city;
    private String periodType;
    private List<TimeRangeEntry> timeRanges;
    private BigDecimal price;

    /**
     * Time range entry (start-end pair)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRangeEntry {
        private String start; // HH:mm
        private String end;   // HH:mm
    }

    /**
     * Batch save wrapper — carries region info + list of price tiers
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ElectricityPriceBatchDTO {
        private String country;
        private String province;
        private String city;
        private List<ProjectElectricityPriceDTO> prices;
    }
}
