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
 * V2G Vehicle Configuration entity
 * Stores vehicle count, battery info, weekly schedule and special dates per project
 */
@Entity
@Table(name = "v2g_vehicle_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class V2gVehicleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "vehicle_count")
    private Integer vehicleCount;

    @Column(name = "battery_capacity_kwh", precision = 10, scale = 2)
    private BigDecimal batteryCapacityKwh;

    @Column(name = "enable_time_control")
    private Boolean enableTimeControl;

    @Column(name = "weekly_schedule", columnDefinition = "JSON")
    private String weeklySchedule;

    @Column(name = "special_dates", columnDefinition = "JSON")
    private String specialDates;

    @Column(name = "fast_chargers")
    private Integer fastChargers;

    @Column(name = "slow_chargers")
    private Integer slowChargers;

    @Column(name = "ultra_fast_chargers")
    private Integer ultraFastChargers;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
