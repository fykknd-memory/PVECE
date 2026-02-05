package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.PvSystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * PV System Configuration Repository
 */
@Repository
public interface PvSystemConfigRepository extends JpaRepository<PvSystemConfig, Long> {

    /**
     * Find PV config by project ID
     */
    Optional<PvSystemConfig> findByProjectId(Long projectId);

    /**
     * Delete PV config by project ID
     */
    void deleteByProjectId(Long projectId);
}
