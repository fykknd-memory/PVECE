package com.lynkvertx.pvece.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynkvertx.pvece.dto.ProjectElectricityPriceDTO;
import com.lynkvertx.pvece.dto.ProjectElectricityPriceDTO.ElectricityPriceBatchDTO;
import com.lynkvertx.pvece.dto.ProjectElectricityPriceDTO.TimeRangeEntry;
import com.lynkvertx.pvece.entity.ProjectElectricityPrice;
import com.lynkvertx.pvece.repository.ProjectElectricityPriceRepository;
import com.lynkvertx.pvece.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-project Electricity Price Service
 * Uses delete-then-insert strategy for batch saves (transactional)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectElectricityPriceService {

    private final ProjectElectricityPriceRepository priceRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    public List<ProjectElectricityPriceDTO> getByProjectId(Long projectId) {
        return priceRepository.findByProjectId(projectId)
            .stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    @Transactional
    public List<ProjectElectricityPriceDTO> savePrices(Long projectId, ElectricityPriceBatchDTO batch) {
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found with id: " + projectId);
        }

        // Delete existing prices for this project, then insert new ones
        priceRepository.deleteByProjectId(projectId);

        if (batch.getPrices() == null || batch.getPrices().isEmpty()) {
            return Collections.emptyList();
        }

        List<ProjectElectricityPrice> entities = batch.getPrices().stream()
            .map(dto -> {
                String timeRangesJson;
                try {
                    timeRangesJson = dto.getTimeRanges() != null
                        ? objectMapper.writeValueAsString(dto.getTimeRanges())
                        : null;
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("Failed to serialize time ranges: " + e.getMessage());
                }

                return ProjectElectricityPrice.builder()
                    .projectId(projectId)
                    .country(batch.getCountry())
                    .province(batch.getProvince())
                    .city(batch.getCity())
                    .periodType(dto.getPeriodType())
                    .timeRanges(timeRangesJson)
                    .price(dto.getPrice())
                    .build();
            })
            .collect(Collectors.toList());

        List<ProjectElectricityPrice> saved = priceRepository.saveAll(entities);
        log.info("Saved {} electricity price tiers for project: {}", saved.size(), projectId);

        return saved.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private ProjectElectricityPriceDTO toDTO(ProjectElectricityPrice entity) {
        List<TimeRangeEntry> timeRanges = Collections.emptyList();
        try {
            if (entity.getTimeRanges() != null) {
                timeRanges = objectMapper.readValue(
                    entity.getTimeRanges(),
                    new TypeReference<List<TimeRangeEntry>>() {}
                );
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize time ranges for price {}: {}", entity.getId(), e.getMessage());
        }

        return ProjectElectricityPriceDTO.builder()
            .id(entity.getId())
            .projectId(entity.getProjectId())
            .country(entity.getCountry())
            .province(entity.getProvince())
            .city(entity.getCity())
            .periodType(entity.getPeriodType())
            .timeRanges(timeRanges)
            .price(entity.getPrice())
            .build();
    }
}
