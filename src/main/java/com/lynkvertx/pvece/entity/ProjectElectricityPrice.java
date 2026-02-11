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
 * Per-project Time-of-Use electricity price entity
 * Stores TOU price tiers (peak/high/normal/valley) with time ranges
 */
@Entity
@Table(name = "project_electricity_price")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectElectricityPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "country", length = 10, nullable = false)
    private String country;

    @Column(name = "province", length = 50)
    private String province;

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "period_type", length = 20, nullable = false)
    private String periodType;

    @Column(name = "time_ranges", columnDefinition = "JSON")
    private String timeRanges;

    @Column(name = "price", precision = 10, scale = 4, nullable = false)
    private BigDecimal price;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
