package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Project Repository
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Find projects by name containing the given string (case-insensitive)
     */
    List<Project> findByNameContainingIgnoreCase(String name);

    /**
     * Find all projects ordered by creation time descending
     */
    List<Project> findAllByOrderByCreatedAtDesc();
}
