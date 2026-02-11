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
    transformer_capacity DECIMAL(10,2),
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
    enable_time_control TINYINT(1) DEFAULT 1,
    weekly_schedule JSON,
    special_dates JSON,
    fast_chargers INT,
    slow_chargers INT,
    ultra_fast_chargers INT,
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

-- Per-project Time-of-Use Electricity Prices
CREATE TABLE IF NOT EXISTS project_electricity_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    country VARCHAR(10) NOT NULL DEFAULT 'CN',
    province VARCHAR(50),
    city VARCHAR(50),
    period_type VARCHAR(20) NOT NULL,
    time_ranges JSON,
    price DECIMAL(10,4) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_elec_price_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    INDEX idx_project_elec_price_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================
-- ALTER TABLE statements for existing databases
-- ============================
-- Add transformer_capacity to project table if not exists
-- MySQL does not support IF NOT EXISTS for columns, so use a safe approach:
-- These statements can be run manually if tables already exist without the new columns.

-- ALTER TABLE project ADD COLUMN transformer_capacity DECIMAL(10,2) AFTER location_address;
-- ALTER TABLE v2g_vehicle_config ADD COLUMN enable_time_control TINYINT(1) DEFAULT 1 AFTER battery_capacity_kwh;
-- ALTER TABLE v2g_vehicle_config DROP COLUMN min_soc;
-- ALTER TABLE v2g_vehicle_config ADD COLUMN special_dates JSON AFTER weekly_schedule;
-- ALTER TABLE v2g_vehicle_config ADD COLUMN fast_chargers INT AFTER special_dates;
-- ALTER TABLE v2g_vehicle_config ADD COLUMN slow_chargers INT AFTER fast_chargers;
-- ALTER TABLE v2g_vehicle_config ADD COLUMN ultra_fast_chargers INT AFTER slow_chargers;
