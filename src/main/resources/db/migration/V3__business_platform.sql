-- Additive migration: legacy tables and APIs remain intact.
CREATE TABLE platform_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(80) NOT NULL,
 role VARCHAR(16) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 failed_logins INT NOT NULL DEFAULT 0,
 locked_until TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_dataset (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(120) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 owner_id BIGINT NOT NULL,
 is_example BOOLEAN NOT NULL DEFAULT FALSE,
 raw_revision BIGINT NOT NULL DEFAULT 0,
 active_result_version BIGINT NULL,
 next_result_version BIGINT NOT NULL DEFAULT 1,
 raw_rows BIGINT NOT NULL DEFAULT 0,
 station_count INT NOT NULL DEFAULT 0,
 first_date DATE NULL, last_date DATE NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(owner_id) REFERENCES platform_user(id),
 INDEX idx_dataset_owner(owner_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_member (
 dataset_id BIGINT NOT NULL, user_id BIGINT NOT NULL, permission VARCHAR(8) NOT NULL,
 PRIMARY KEY(dataset_id,user_id),
 FOREIGN KEY(dataset_id) REFERENCES platform_dataset(id), FOREIGN KEY(user_id) REFERENCES platform_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_file (
 id CHAR(36) PRIMARY KEY, dataset_id BIGINT NOT NULL, uploader_id BIGINT NOT NULL,
 original_name VARCHAR(160) NOT NULL, sha256 CHAR(64) NOT NULL, file_size BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'STAGED', imported_rows INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_platform_file(dataset_id,sha256),
 FOREIGN KEY(dataset_id) REFERENCES platform_dataset(id), FOREIGN KEY(uploader_id) REFERENCES platform_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_task (
 id CHAR(36) PRIMARY KEY, dataset_id BIGINT NOT NULL, created_by BIGINT NOT NULL,
 task_type VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 phase VARCHAR(32) NOT NULL DEFAULT 'WAITING', processed_rows BIGINT NOT NULL DEFAULT 0,
 file_id CHAR(36) NULL, payload LONGTEXT NOT NULL,
 input_revision BIGINT NULL, result_version BIGINT NULL, retry_of CHAR(36) NULL,
 error_code VARCHAR(64) NULL, error_message VARCHAR(600) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 started_at TIMESTAMP(6) NULL, finished_at TIMESTAMP(6) NULL,
 FOREIGN KEY(dataset_id) REFERENCES platform_dataset(id), FOREIGN KEY(created_by) REFERENCES platform_user(id),
 FOREIGN KEY(file_id) REFERENCES platform_file(id),
 INDEX idx_platform_task_queue(status,created_at,id), INDEX idx_platform_task_dataset(dataset_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_queue_guard (id TINYINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO platform_queue_guard VALUES(1);

CREATE TABLE platform_analysis_run (
 dataset_id BIGINT NOT NULL, result_version BIGINT NOT NULL, input_revision BIGINT NOT NULL,
 formula_version VARCHAR(40) NOT NULL, task_id CHAR(36) NULL,
 raw_rows BIGINT NOT NULL, analysis_rows BIGINT NOT NULL, missing_threshold_rows BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(dataset_id,result_version), FOREIGN KEY(dataset_id) REFERENCES platform_dataset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_export (
 id CHAR(36) PRIMARY KEY, task_id CHAR(36) NOT NULL UNIQUE, dataset_id BIGINT NOT NULL,
 result_version BIGINT NOT NULL, row_count INT NOT NULL, file_size BIGINT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES platform_task(id), FOREIGN KEY(dataset_id) REFERENCES platform_dataset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NOT NULL, dataset_id BIGINT NULL,
 action VARCHAR(40) NOT NULL, target_id VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_platform_audit_dataset(dataset_id,id), FOREIGN KEY(actor_id) REFERENCES platform_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_raw_observation LIKE raw_observation;
ALTER TABLE platform_raw_observation ADD dataset_id BIGINT NOT NULL FIRST,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,station_code,observation_date),
 ADD INDEX idx_platform_raw_date(dataset_id,observation_date);

CREATE TABLE platform_station_threshold LIKE station_threshold;
ALTER TABLE platform_station_threshold ADD dataset_id BIGINT NOT NULL FIRST,
 ADD result_version BIGINT NOT NULL AFTER dataset_id,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,result_version,station_code);

CREATE TABLE platform_daily_indicator LIKE daily_indicator;
ALTER TABLE platform_daily_indicator ADD dataset_id BIGINT NOT NULL FIRST,
 ADD result_version BIGINT NOT NULL AFTER dataset_id,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,result_version,station_code,observation_date),
 ADD INDEX idx_platform_daily_region(dataset_id,result_version,country,province,city,observation_date);

CREATE TABLE platform_station_year_stat LIKE station_year_stat;
ALTER TABLE platform_station_year_stat ADD dataset_id BIGINT NOT NULL FIRST,
 ADD result_version BIGINT NOT NULL AFTER dataset_id,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,result_version,station_code,observation_year,metric);

CREATE TABLE platform_region_daily_stat LIKE region_daily_stat;
ALTER TABLE platform_region_daily_stat ADD dataset_id BIGINT NOT NULL FIRST,
 ADD result_version BIGINT NOT NULL AFTER dataset_id,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,result_version,observation_date,region_level,country,province,city,district,metric);

CREATE TABLE platform_region_year_stat LIKE region_year_stat;
ALTER TABLE platform_region_year_stat ADD dataset_id BIGINT NOT NULL FIRST,
 ADD result_version BIGINT NOT NULL AFTER dataset_id,
 DROP PRIMARY KEY, ADD PRIMARY KEY(dataset_id,result_version,observation_year,region_level,country,province,city,district,metric);

CREATE VIEW platform_indicator_value AS
 SELECT dataset_id,result_version,station_code,observation_date,'LTD' metric,ltd value FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'HTD',htd FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'HR',hr FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'EHR',ehr FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'LH',lh FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'HTD1',htd1 FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'CTD1',ctd1 FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'FOG',fog FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'RAIN',rain FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'SNOW',snow FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'HAIL',hail FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'THUNDER',thunder FROM platform_daily_indicator
 UNION ALL SELECT dataset_id,result_version,station_code,observation_date,'TORNADO',tornado FROM platform_daily_indicator;
