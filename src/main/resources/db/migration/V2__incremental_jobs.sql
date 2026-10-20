CREATE TABLE incremental_file_job (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  sha256 CHAR(64) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_size BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 1,
  imported_rows INT NOT NULL DEFAULT 0,
  affected_stations INT NOT NULL DEFAULT 0,
  revision BIGINT NULL,
  error_code VARCHAR(64) NULL,
  completed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  next_retry_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_incremental_sha256(sha256),
  INDEX idx_incremental_completed(completed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Transaction-local working rows, inserted and removed in the same transaction.
-- A normal InnoDB table avoids MySQL's multiple-reference limitation for TEMPORARY tables.
CREATE TABLE analysis_scope (
  scope_id CHAR(36) NOT NULL,
  station_code CHAR(11) NOT NULL,
  PRIMARY KEY(scope_id,station_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
