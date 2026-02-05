package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Storage System Configuration Data Transfer Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageSystemConfigDTO {

    private Long id;

    private Long projectId;

    private BigDecimal batteryCapacityKwh;

    private BigDecimal efficiencyPercent;

    private BigDecimal dodPercent;
}
