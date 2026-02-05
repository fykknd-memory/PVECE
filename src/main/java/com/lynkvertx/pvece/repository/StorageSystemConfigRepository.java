package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.StorageSystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Storage System Configuration Repository
 */
@Repository
public interface StorageSystemConfigRepository extends JpaRepository<StorageSystemConfig, Long> {

    /**
     * Find storage config by project ID
     */
    Optional<StorageSystemConfig> findByProjectId(Long projectId);

    /**
     * Delete storage config by project ID
     */
    void deleteByProjectId(Long projectId);
}
