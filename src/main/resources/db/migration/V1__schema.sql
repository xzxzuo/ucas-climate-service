CREATE TABLE raw_observation (
  station_code CHAR(11) NOT NULL,
  observation_date DATE NOT NULL,
  station_name VARCHAR(160) NOT NULL DEFAULT '',
  temp_f DECIMAL(10,4), precip_in DECIMAL(10,4), humidity_ratio DECIMAL(14,10),
  max_temp_f DECIMAL(10,4), min_temp_f DECIMAL(10,4), weather_flags CHAR(6),
  country VARCHAR(96) NOT NULL DEFAULT '', province VARCHAR(96) NOT NULL DEFAULT '',
  city VARCHAR(96) NOT NULL DEFAULT '', district VARCHAR(96) NOT NULL DEFAULT '',
  PRIMARY KEY(station_code, observation_date),
  INDEX idx_raw_date(observation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE station_threshold (
  station_code CHAR(11) PRIMARY KEY,
  temp10 DECIMAL(10,4), temp90 DECIMAL(10,4), prcp95 DECIMAL(10,4), prcp99 DECIMAL(10,4), humidity5 DECIMAL(14,10),
  temp_samples INT NOT NULL, prcp_samples INT NOT NULL, humidity_samples INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE daily_indicator (
  station_code CHAR(11) NOT NULL, observation_date DATE NOT NULL,
  country VARCHAR(96) NOT NULL, province VARCHAR(96) NOT NULL, city VARCHAR(96) NOT NULL, district VARCHAR(96) NOT NULL,
  ltd DECIMAL(14,10), htd DECIMAL(14,10), hr DECIMAL(14,10), ehr DECIMAL(14,10), lh DECIMAL(14,10),
  htd1 DECIMAL(14,10), ctd1 DECIMAL(14,10),
  fog TINYINT, rain TINYINT, snow TINYINT, hail TINYINT, thunder TINYINT, tornado TINYINT,
  PRIMARY KEY(station_code, observation_date),
  INDEX idx_daily_region(country, province, city, observation_date),
  INDEX idx_daily_date(observation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE VIEW indicator_value AS
 SELECT station_code, observation_date, 'LTD' metric, ltd value FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'HTD', htd FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'HR', hr FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'EHR', ehr FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'LH', lh FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'HTD1', htd1 FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'CTD1', ctd1 FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'FOG', fog FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'RAIN', rain FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'SNOW', snow FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'HAIL', hail FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'THUNDER', thunder FROM daily_indicator
 UNION ALL SELECT station_code, observation_date, 'TORNADO', tornado FROM daily_indicator;

CREATE VIEW region_levels AS
 SELECT 'COUNTRY' region_level, 1 depth
 UNION ALL SELECT 'PROVINCE', 2 UNION ALL SELECT 'CITY', 3 UNION ALL SELECT 'DISTRICT', 4;

CREATE TABLE region_daily_stat (
  observation_date DATE NOT NULL, region_level VARCHAR(8) NOT NULL,
  country VARCHAR(96) NOT NULL, province VARCHAR(96) NOT NULL, city VARCHAR(96) NOT NULL, district VARCHAR(96) NOT NULL,
  metric VARCHAR(8) NOT NULL,
  avg_value DECIMAL(18,10), min_value DECIMAL(18,10), max_value DECIMAL(18,10), median_value DECIMAL(18,10),
  valid_station_count INT NOT NULL,
  PRIMARY KEY(observation_date, region_level, country, province, city, district, metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE station_year_stat (
  station_code CHAR(11) NOT NULL, observation_year SMALLINT NOT NULL, metric VARCHAR(8) NOT NULL,
  country VARCHAR(96) NOT NULL, province VARCHAR(96) NOT NULL, city VARCHAR(96) NOT NULL, district VARCHAR(96) NOT NULL,
  nonzero_days INT NOT NULL, valid_days INT NOT NULL,
  PRIMARY KEY(station_code, observation_year, metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE region_year_stat (
  observation_year SMALLINT NOT NULL, region_level VARCHAR(8) NOT NULL,
  country VARCHAR(96) NOT NULL, province VARCHAR(96) NOT NULL, city VARCHAR(96) NOT NULL, district VARCHAR(96) NOT NULL,
  metric VARCHAR(8) NOT NULL,
  avg_days DECIMAL(12,4), min_days INT, max_days INT, median_days DECIMAL(12,4), station_count INT NOT NULL,
  PRIMARY KEY(observation_year, region_level, country, province, city, district, metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE analysis_state (
  id TINYINT PRIMARY KEY, revision BIGINT NOT NULL, calculated_at TIMESTAMP NULL,
  raw_rows BIGINT NOT NULL DEFAULT 0, analysis_rows BIGINT NOT NULL DEFAULT 0, missing_threshold_rows BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO analysis_state(id, revision) VALUES(1, 0);
