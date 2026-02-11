package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.V2gVehicleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * V2G Vehicle Configuration Repository
 */
@Repository
public interface V2gVehicleConfigRepository extends JpaRepository<V2gVehicleConfig, Long> {

    Optional<V2gVehicleConfig> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
