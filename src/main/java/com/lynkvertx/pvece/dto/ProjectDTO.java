package com.lynkvertx.pvece.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Project Data Transfer Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDTO {

    private Long id;

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must not exceed 255 characters")
    private String name;

    private BigDecimal locationLat;

    private BigDecimal locationLng;

    @Size(max = 500, message = "Location address must not exceed 500 characters")
    private String locationAddress;

    private BigDecimal transformerCapacity;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
