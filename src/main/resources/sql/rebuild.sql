-- All statements are DML. AnalysisService executes them in ONE transaction.
DELETE FROM region_year_stat;
DELETE FROM station_year_stat WHERE __STATION_FILTER__;
DELETE FROM region_daily_stat;
DELETE FROM daily_indicator WHERE __STATION_FILTER__;
DELETE FROM station_threshold WHERE __STATION_FILTER__;

-- Task 1: empirical nearest-rank quantiles, rank = CEIL(p*n), excluding NULL per variable.
-- This is an explicit reconstruction definition, not an assertion that the old scripts used it.
INSERT INTO station_threshold
WITH valid_values AS (
 SELECT station_code, 'TEMP' variable, temp_f value FROM raw_observation
 WHERE observation_date >= '1973-01-01' AND observation_date < '1993-01-01' AND temp_f IS NOT NULL AND __STATION_FILTER__
 UNION ALL SELECT station_code, 'PRCP', precip_in FROM raw_observation
 WHERE observation_date >= '1973-01-01' AND observation_date < '1993-01-01' AND precip_in IS NOT NULL AND __STATION_FILTER__
 UNION ALL SELECT station_code, 'HUMIDITY', humidity_ratio FROM raw_observation
 WHERE observation_date >= '1973-01-01' AND observation_date < '1993-01-01' AND humidity_ratio IS NOT NULL AND __STATION_FILTER__
), ranked AS (
 SELECT *, ROW_NUMBER() OVER(PARTITION BY station_code, variable ORDER BY value) rn,
 COUNT(*) OVER(PARTITION BY station_code, variable) n FROM valid_values
)
SELECT station_code,
 MAX(CASE WHEN variable='TEMP' AND rn=CEIL(0.10*n) THEN value END),
 MAX(CASE WHEN variable='TEMP' AND rn=CEIL(0.90*n) THEN value END),
 MAX(CASE WHEN variable='PRCP' AND rn=CEIL(0.95*n) THEN value END),
 MAX(CASE WHEN variable='PRCP' AND rn=CEIL(0.99*n) THEN value END),
 MAX(CASE WHEN variable='HUMIDITY' AND rn=CEIL(0.05*n) THEN value END),
 COALESCE(MAX(CASE WHEN variable='TEMP' THEN n END),0),
 COALESCE(MAX(CASE WHEN variable='PRCP' THEN n END),0),
 COALESCE(MAX(CASE WHEN variable='HUMIDITY' THEN n END),0)
FROM ranked GROUP BY station_code
HAVING COALESCE(MAX(CASE WHEN variable='TEMP' THEN n END),0) >= __MIN_SAMPLES__
 AND COALESCE(MAX(CASE WHEN variable='PRCP' THEN n END),0) >= __MIN_SAMPLES__
 AND COALESCE(MAX(CASE WHEN variable='HUMIDITY' THEN n END),0) >= __MIN_SAMPLES__;

-- Task 2: seven magnitudes + six weather flags; retain NULL as missing, not as zero.
INSERT INTO daily_indicator
SELECT d.station_code, d.observation_date, d.country, d.province, d.city, d.district,
 GREATEST(0, p.temp10-d.temp_f), GREATEST(0,d.temp_f-p.temp90),
 GREATEST(0,d.precip_in-p.prcp95), GREATEST(0,d.precip_in-p.prcp99),
 GREATEST(0,p.humidity5-d.humidity_ratio),
 GREATEST(0,d.max_temp_f-95), GREATEST(0,5-d.min_temp_f),
 CAST(SUBSTRING(weather_flags,1,1) AS UNSIGNED), CAST(SUBSTRING(weather_flags,2,1) AS UNSIGNED),
 CAST(SUBSTRING(weather_flags,3,1) AS UNSIGNED), CAST(SUBSTRING(weather_flags,4,1) AS UNSIGNED),
 CAST(SUBSTRING(weather_flags,5,1) AS UNSIGNED), CAST(SUBSTRING(weather_flags,6,1) AS UNSIGNED)
FROM raw_observation d JOIN station_threshold p ON d.station_code=p.station_code
WHERE d.observation_date >= '1993-01-01' AND __DAILY_FILTER__;

-- Task 3: date MUST be part of the region grouping/window partition.
INSERT INTO region_daily_stat
WITH expanded AS (
 SELECT d.observation_date, l.region_level, d.country,
 CASE WHEN depth>=2 THEN d.province ELSE '' END province,
 CASE WHEN depth>=3 THEN d.city ELSE '' END city,
 CASE WHEN depth>=4 THEN d.district ELSE '' END district, v.metric, v.value
 FROM daily_indicator d JOIN indicator_value v USING(station_code,observation_date)
 CROSS JOIN region_levels l
 WHERE v.metric IN('LTD','HTD','HR','EHR','LH','HTD1','CTD1') AND v.value IS NOT NULL
 AND d.country<>'' AND (depth<2 OR d.province<>'') AND (depth<3 OR d.city<>'')
 AND (depth<4 OR (d.country='中国' AND d.district<>''))
), ranked AS (
 SELECT *, ROW_NUMBER() OVER(PARTITION BY observation_date,region_level,country,province,city,district,metric ORDER BY value) rn,
 COUNT(*) OVER(PARTITION BY observation_date,region_level,country,province,city,district,metric) n FROM expanded
)
SELECT observation_date,region_level,country,province,city,district,metric,
 AVG(value),MIN(value),MAX(value),
 AVG(CASE WHEN rn IN(FLOOR((n+1)/2),FLOOR((n+2)/2)) THEN value END),COUNT(*)
FROM ranked GROUP BY observation_date,region_level,country,province,city,district,metric;

-- Task 4a: frequency means non-zero DAYS among available observations, NOT probability.
INSERT INTO station_year_stat
SELECT d.station_code,YEAR(d.observation_date),v.metric,d.country,d.province,d.city,d.district,
 COUNT(CASE WHEN v.value>0 THEN 1 END),COUNT(v.value)
FROM daily_indicator d JOIN indicator_value v USING(station_code,observation_date)
WHERE __DAILY_FILTER__
GROUP BY d.station_code,YEAR(d.observation_date),v.metric,d.country,d.province,d.city,d.district;

-- Task 4b: regional statistics of station-year non-zero day counts.
INSERT INTO region_year_stat
WITH expanded AS (
 SELECT s.observation_year,l.region_level,s.country,
 CASE WHEN depth>=2 THEN s.province ELSE '' END province,
 CASE WHEN depth>=3 THEN s.city ELSE '' END city,
 CASE WHEN depth>=4 THEN s.district ELSE '' END district,s.metric,s.nonzero_days
 FROM station_year_stat s CROSS JOIN region_levels l
 WHERE s.valid_days>0 AND s.country<>'' AND (depth<2 OR s.province<>'') AND (depth<3 OR s.city<>'')
 AND (depth<4 OR (s.country='中国' AND s.district<>''))
), ranked AS (
 SELECT *,ROW_NUMBER() OVER(PARTITION BY observation_year,region_level,country,province,city,district,metric ORDER BY nonzero_days) rn,
 COUNT(*) OVER(PARTITION BY observation_year,region_level,country,province,city,district,metric) n FROM expanded
)
SELECT observation_year,region_level,country,province,city,district,metric,
 AVG(nonzero_days),MIN(nonzero_days),MAX(nonzero_days),
 AVG(CASE WHEN rn IN(FLOOR((n+1)/2),FLOOR((n+2)/2)) THEN nonzero_days END),COUNT(*)
FROM ranked GROUP BY observation_year,region_level,country,province,city,district,metric;

UPDATE analysis_state SET revision=revision+1,calculated_at=CURRENT_TIMESTAMP,
 raw_rows=(SELECT COUNT(*) FROM raw_observation),analysis_rows=(SELECT COUNT(*) FROM daily_indicator),
 missing_threshold_rows=(SELECT COUNT(*) FROM raw_observation r LEFT JOIN station_threshold t USING(station_code)
 WHERE r.observation_date>='1993-01-01' AND t.station_code IS NULL)
WHERE id=1;
