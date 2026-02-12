package com.lynkvertx.pvece.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynkvertx.pvece.dto.V2gVehicleConfigDTO;
import com.lynkvertx.pvece.entity.V2gVehicleConfig;
import com.lynkvertx.pvece.repository.ProjectRepository;
import com.lynkvertx.pvece.repository.V2gVehicleConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * V2G Vehicle Configuration Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class V2gVehicleConfigService {

    private final V2gVehicleConfigRepository v2gConfigRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    public Optional<V2gVehicleConfigDTO> getByProjectId(Long projectId) {
        return v2gConfigRepository.findByProjectId(projectId)
            .map(this::toDTO);
    }

    @Transactional
    public V2gVehicleConfigDTO saveConfig(Long projectId, V2gVehicleConfigDTO dto) {
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found with id: " + projectId);
        }

        V2gVehicleConfig config = v2gConfigRepository.findByProjectId(projectId)
            .orElse(new V2gVehicleConfig());

        config.setProjectId(projectId);
        config.setVehicleCount(dto.getVehicleCount());
        config.setBatteryCapacityKwh(dto.getBatteryCapacityKwh());
        config.setEnableTimeControl(dto.getEnableTimeControl());
        config.setFastChargers(dto.getFastChargers());
        config.setSlowChargers(dto.getSlowChargers());
        config.setUltraFastChargers(dto.getUltraFastChargers());
        config.setFastChargersV2g(dto.getFastChargersV2g());
        config.setSlowChargersV2g(dto.getSlowChargersV2g());
        config.setUltraFastChargersV2g(dto.getUltraFastChargersV2g());

        try {
            config.setWeeklySchedule(
                dto.getWeeklySchedule() != null
                    ? objectMapper.writeValueAsString(dto.getWeeklySchedule())
                    : null
            );
            config.setSpecialDates(
                dto.getSpecialDates() != null
                    ? objectMapper.writeValueAsString(dto.getSpecialDates())
                    : null
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize schedule data: " + e.getMessage());
        }

        V2gVehicleConfig saved = v2gConfigRepository.save(config);
        log.info("Saved V2G config for project: {}", projectId);
        return toDTO(saved);
    }

    private V2gVehicleConfigDTO toDTO(V2gVehicleConfig entity) {
        List<V2gVehicleConfigDTO.WeeklyScheduleEntry> weeklySchedule = Collections.emptyList();
        List<V2gVehicleConfigDTO.SpecialDateEntry> specialDates = Collections.emptyList();

        try {
            if (entity.getWeeklySchedule() != null) {
                weeklySchedule = objectMapper.readValue(
                    entity.getWeeklySchedule(),
                    new TypeReference<List<V2gVehicleConfigDTO.WeeklyScheduleEntry>>() {}
                );
            }
            if (entity.getSpecialDates() != null) {
                specialDates = objectMapper.readValue(
                    entity.getSpecialDates(),
                    new TypeReference<List<V2gVehicleConfigDTO.SpecialDateEntry>>() {}
                );
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize schedule data for project {}: {}", entity.getProjectId(), e.getMessage());
        }

        return V2gVehicleConfigDTO.builder()
            .id(entity.getId())
            .projectId(entity.getProjectId())
            .vehicleCount(entity.getVehicleCount())
            .batteryCapacityKwh(entity.getBatteryCapacityKwh())
            .enableTimeControl(entity.getEnableTimeControl())
            .weeklySchedule(weeklySchedule)
            .specialDates(specialDates)
            .fastChargers(entity.getFastChargers())
            .slowChargers(entity.getSlowChargers())
            .ultraFastChargers(entity.getUltraFastChargers())
            .fastChargersV2g(entity.getFastChargersV2g())
            .slowChargersV2g(entity.getSlowChargersV2g())
            .ultraFastChargersV2g(entity.getUltraFastChargersV2g())
            .build();
    }
}
