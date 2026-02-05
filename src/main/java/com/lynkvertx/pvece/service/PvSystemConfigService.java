package com.lynkvertx.pvece.service;

import com.lynkvertx.pvece.dto.PvSystemConfigDTO;
import com.lynkvertx.pvece.entity.PvSystemConfig;
import com.lynkvertx.pvece.repository.PvSystemConfigRepository;
import com.lynkvertx.pvece.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.Optional;

/**
 * PV System Configuration Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PvSystemConfigService {

    private final PvSystemConfigRepository pvConfigRepository;
    private final ProjectRepository projectRepository;

    /**
     * Get PV config by project ID
     */
    public Optional<PvSystemConfigDTO> getByProjectId(Long projectId) {
        return pvConfigRepository.findByProjectId(projectId)
            .map(this::toDTO);
    }

    /**
     * Save or update PV config for a project
     */
    @Transactional
    public PvSystemConfigDTO saveConfig(Long projectId, PvSystemConfigDTO dto) {
        // Verify project exists
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found with id: " + projectId);
        }

        PvSystemConfig config = pvConfigRepository.findByProjectId(projectId)
            .orElse(new PvSystemConfig());

        config.setProjectId(projectId);
        config.setInstalledCapacityKw(dto.getInstalledCapacityKw());
        config.setFirstYearGenHours(dto.getFirstYearGenHours());
        config.setUserElectricityPrice(dto.getUserElectricityPrice());
        config.setDesulfurizationPrice(dto.getDesulfurizationPrice());
        config.setElectricitySubsidy(dto.getElectricitySubsidy());

        PvSystemConfig saved = pvConfigRepository.save(config);
        log.info("Saved PV config for project: {}", projectId);
        return toDTO(saved);
    }

    /**
     * Convert entity to DTO
     */
    private PvSystemConfigDTO toDTO(PvSystemConfig entity) {
        return PvSystemConfigDTO.builder()
            .id(entity.getId())
            .projectId(entity.getProjectId())
            .installedCapacityKw(entity.getInstalledCapacityKw())
            .firstYearGenHours(entity.getFirstYearGenHours())
            .userElectricityPrice(entity.getUserElectricityPrice())
            .desulfurizationPrice(entity.getDesulfurizationPrice())
            .electricitySubsidy(entity.getElectricitySubsidy())
            .build();
    }
}
