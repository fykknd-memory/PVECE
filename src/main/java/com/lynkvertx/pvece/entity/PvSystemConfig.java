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
 * Photovoltaic System Configuration entity
 * Stores user-filled PV system parameters
 */
@Entity
@Table(name = "pv_system_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PvSystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "installed_capacity_kw", precision = 10, scale = 2)
    private BigDecimal installedCapacityKw;

    @Column(name = "first_year_gen_hours", precision = 8, scale = 2)
    private BigDecimal firstYearGenHours;

    @Column(name = "user_electricity_price", precision = 10, scale = 4)
    private BigDecimal userElectricityPrice;

    @Column(name = "desulfurization_price", precision = 10, scale = 4)
    private BigDecimal desulfurizationPrice;

    @Column(name = "electricity_subsidy", precision = 10, scale = 4)
    private BigDecimal electricitySubsidy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;
}
