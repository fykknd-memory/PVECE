package com.lynkvertx.pvece.service;

import com.lynkvertx.pvece.dto.ProjectDTO;
import com.lynkvertx.pvece.entity.Project;
import com.lynkvertx.pvece.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Project Service
 * Handles business logic for project operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    /**
     * Get all projects
     */
    public List<ProjectDTO> getAllProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
            .stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    /**
     * Get project by ID
     */
    public ProjectDTO getProjectById(Long id) {
        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));
        return toDTO(project);
    }

    /**
     * Create a new project
     */
    @Transactional
    public ProjectDTO createProject(ProjectDTO dto) {
        Project project = toEntity(dto);
        Project saved = projectRepository.save(project);
        log.info("Created new project with id: {}", saved.getId());
        return toDTO(saved);
    }

    /**
     * Update an existing project
     */
    @Transactional
    public ProjectDTO updateProject(Long id, ProjectDTO dto) {
        Project existing = projectRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Project not found with id: " + id));

        existing.setName(dto.getName());
        existing.setLocationLat(dto.getLocationLat());
        existing.setLocationLng(dto.getLocationLng());
        existing.setLocationAddress(dto.getLocationAddress());

        Project saved = projectRepository.save(existing);
        log.info("Updated project with id: {}", saved.getId());
        return toDTO(saved);
    }

    /**
     * Delete a project
     */
    @Transactional
    public void deleteProject(Long id) {
        if (!projectRepository.existsById(id)) {
            throw new EntityNotFoundException("Project not found with id: " + id);
        }
        projectRepository.deleteById(id);
        log.info("Deleted project with id: {}", id);
    }

    /**
     * Convert entity to DTO
     */
    private ProjectDTO toDTO(Project entity) {
        return ProjectDTO.builder()
            .id(entity.getId())
            .name(entity.getName())
            .locationLat(entity.getLocationLat())
            .locationLng(entity.getLocationLng())
            .locationAddress(entity.getLocationAddress())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * Convert DTO to entity
     */
    private Project toEntity(ProjectDTO dto) {
        return Project.builder()
            .name(dto.getName())
            .locationLat(dto.getLocationLat())
            .locationLng(dto.getLocationLng())
            .locationAddress(dto.getLocationAddress())
            .build();
    }
}
