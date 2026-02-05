package com.lynkvertx.pvece.repository;

import com.lynkvertx.pvece.entity.AdminPvParams;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Admin PV Parameters Repository
 */
@Repository
public interface AdminPvParamsRepository extends JpaRepository<AdminPvParams, Long> {
}
