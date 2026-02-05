package com.lynkvertx.pvece.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin PV Parameters entity
 * Stores backend-only configuration parameters (admin access only)
 */
@Entity
@Table(name = "admin_pv_params")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPvParams {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "annual_operating_cost", precision = 10, scale = 4)
    private BigDecimal annualOperatingCost;

    @Column(name = "depreciation_years")
    private Integer depreciationYears;

    @Column(name = "residual_value_percent", precision = 5, scale = 2)
    private BigDecimal residualValuePercent;

    @Column(name = "self_use_ratio", precision = 5, scale = 2)
    private BigDecimal selfUseRatio;

    @Column(name = "electricity_discount", precision = 10, scale = 4)
    private BigDecimal electricityDiscount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
