-- PVECE Database Schema for MySQL 8.0 (UTF8mb4)
-- This file is for reference and production deployment
-- In development, JPA/Hibernate handles schema creation

-- Project/Station information
CREATE TABLE IF NOT EXISTS project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    location_lat DECIMAL(10,7),
    location_lng DECIMAL(10,7),
    location_address VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project_name (name),
    INDEX idx_project_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Photovoltaic system parameters (user-filled part)
CREATE TABLE IF NOT EXISTS pv_system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    installed_capacity_kw DECIMAL(10,2),
    first_year_gen_hours DECIMAL(8,2),
    user_electricity_price DECIMAL(10,4),
    desulfurization_price DECIMAL(10,4),
    electricity_subsidy DECIMAL(10,4),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_pv_config_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    UNIQUE INDEX idx_pv_config_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Energy storage system parameters
CREATE TABLE IF NOT EXISTS storage_system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    battery_capacity_kwh DECIMAL(10,2),
    efficiency_percent DECIMAL(5,2),
    dod_percent DECIMAL(5,2),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_storage_config_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    UNIQUE INDEX idx_storage_config_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Admin-only backend parameters
CREATE TABLE IF NOT EXISTS admin_pv_params (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    annual_operating_cost DECIMAL(10,4),
    depreciation_years INT,
    residual_value_percent DECIMAL(5,2),
    self_use_ratio DECIMAL(5,2),
    electricity_discount DECIMAL(10,4),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Historical PV generation data
CREATE TABLE IF NOT EXISTS pv_generation_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    timestamp DATETIME NOT NULL,
    generation_kwh DECIMAL(10,4),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pv_history_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    INDEX idx_pv_history_project_time (project_id, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V2G Vehicle Configuration
CREATE TABLE IF NOT EXISTS v2g_vehicle_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    vehicle_count INT,
    battery_capacity_kwh DECIMAL(10,2),
    weekly_schedule JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_v2g_config_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    UNIQUE INDEX idx_v2g_config_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Time-of-Use Electricity Prices
CREATE TABLE IF NOT EXISTS electricity_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    region_code VARCHAR(50) NOT NULL,
    period_type ENUM('peak', 'high', 'normal', 'valley') NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    price DECIMAL(10,4) NOT NULL,
    effective_date DATE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_price_region (region_code),
    INDEX idx_price_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Calculation Results
CREATE TABLE IF NOT EXISTS calculation_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    result_type VARCHAR(50) NOT NULL,
    result_data JSON NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_calc_result_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    INDEX idx_calc_result_project_type (project_id, result_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Reports
CREATE TABLE IF NOT EXISTS report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    file_path VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    INDEX idx_report_project (project_id),
    INDEX idx_report_type (report_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
