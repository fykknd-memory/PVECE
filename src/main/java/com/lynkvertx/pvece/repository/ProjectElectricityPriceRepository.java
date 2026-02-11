package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.ProjectElectricityPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Per-project Electricity Price Repository
 */
@Repository
public interface ProjectElectricityPriceRepository extends JpaRepository<ProjectElectricityPrice, Long> {

    List<ProjectElectricityPrice> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
