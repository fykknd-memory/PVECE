package com.lynkvertx.pvece.service;

import com.lynkvertx.pvece.dto.StorageSystemConfigDTO;
import com.lynkvertx.pvece.entity.StorageSystemConfig;
import com.lynkvertx.pvece.repository.ProjectRepository;
import com.lynkvertx.pvece.repository.StorageSystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.Optional;

/**
 * Storage System Configuration Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSystemConfigService {

    private final StorageSystemConfigRepository storageConfigRepository;
    private final ProjectRepository projectRepository;

    /**
     * Get storage config by project ID
     */
    public Optional<StorageSystemConfigDTO> getByProjectId(Long projectId) {
        return storageConfigRepository.findByProjectId(projectId)
            .map(this::toDTO);
    }

    /**
     * Save or update storage config for a project
     */
    @Transactional
    public StorageSystemConfigDTO saveConfig(Long projectId, StorageSystemConfigDTO dto) {
        // Verify project exists
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found with id: " + projectId);
        }

        StorageSystemConfig config = storageConfigRepository.findByProjectId(projectId)
            .orElse(new StorageSystemConfig());

        config.setProjectId(projectId);
        config.setBatteryCapacityKwh(dto.getBatteryCapacityKwh());
        config.setEfficiencyPercent(dto.getEfficiencyPercent());
        config.setDodPercent(dto.getDodPercent());

        StorageSystemConfig saved = storageConfigRepository.save(config);
        log.info("Saved storage config for project: {}", projectId);
        return toDTO(saved);
    }

    /**
     * Convert entity to DTO
     */
    private StorageSystemConfigDTO toDTO(StorageSystemConfig entity) {
        return StorageSystemConfigDTO.builder()
            .id(entity.getId())
            .projectId(entity.getProjectId())
            .batteryCapacityKwh(entity.getBatteryCapacityKwh())
            .efficiencyPercent(entity.getEfficiencyPercent())
            .dodPercent(entity.getDodPercent())
            .build();
    }
}
