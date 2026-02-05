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
 * Energy Storage System Configuration entity
 * Stores storage system parameters
 */
@Entity
@Table(name = "storage_system_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageSystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "battery_capacity_kwh", precision = 10, scale = 2)
    private BigDecimal batteryCapacityKwh;

    @Column(name = "efficiency_percent", precision = 5, scale = 2)
    private BigDecimal efficiencyPercent;

    @Column(name = "dod_percent", precision = 5, scale = 2)
    private BigDecimal dodPercent;

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
