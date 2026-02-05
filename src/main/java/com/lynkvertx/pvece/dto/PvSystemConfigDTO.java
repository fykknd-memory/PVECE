package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * PV System Configuration Data Transfer Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PvSystemConfigDTO {

    private Long id;

    private Long projectId;

    private BigDecimal installedCapacityKw;

    private BigDecimal firstYearGenHours;

    private BigDecimal userElectricityPrice;

    private BigDecimal desulfurizationPrice;

    private BigDecimal electricitySubsidy;
}
