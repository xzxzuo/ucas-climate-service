-- Only server-generated numeric dataset/version IDs are substituted. Original formulas unchanged.
INSERT INTO platform_station_threshold
WITH valid_values AS (
 SELECT station_code,'TEMP' variable,temp_f value FROM platform_raw_observation
 WHERE dataset_id=__DATASET__ AND observation_date>='1973-01-01' AND observation_date<'1993-01-01' AND temp_f IS NOT NULL
 UNION ALL SELECT station_code,'PRCP',precip_in FROM platform_raw_observation
 WHERE dataset_id=__DATASET__ AND observation_date>='1973-01-01' AND observation_date<'1993-01-01' AND precip_in IS NOT NULL
 UNION ALL SELECT station_code,'HUMIDITY',humidity_ratio FROM platform_raw_observation
 WHERE dataset_id=__DATASET__ AND observation_date>='1973-01-01' AND observation_date<'1993-01-01' AND humidity_ratio IS NOT NULL
), ranked AS (
 SELECT *,ROW_NUMBER() OVER(PARTITION BY station_code,variable ORDER BY value) rn,
 COUNT(*) OVER(PARTITION BY station_code,variable) n FROM valid_values
)
SELECT __DATASET__,__VERSION__,station_code,
 MAX(CASE WHEN variable='TEMP' AND rn=CEIL(0.10*n) THEN value END),
 MAX(CASE WHEN variable='TEMP' AND rn=CEIL(0.90*n) THEN value END),
 MAX(CASE WHEN variable='PRCP' AND rn=CEIL(0.95*n) THEN value END),
 MAX(CASE WHEN variable='PRCP' AND rn=CEIL(0.99*n) THEN value END),
 MAX(CASE WHEN variable='HUMIDITY' AND rn=CEIL(0.05*n) THEN value END),
 COALESCE(MAX(CASE WHEN variable='TEMP' THEN n END),0),COALESCE(MAX(CASE WHEN variable='PRCP' THEN n END),0),
 COALESCE(MAX(CASE WHEN variable='HUMIDITY' THEN n END),0)
FROM ranked GROUP BY station_code
HAVING COALESCE(MAX(CASE WHEN variable='TEMP' THEN n END),0)>=__MIN_SAMPLES__
 AND COALESCE(MAX(CASE WHEN variable='PRCP' THEN n END),0)>=__MIN_SAMPLES__
 AND COALESCE(MAX(CASE WHEN variable='HUMIDITY' THEN n END),0)>=__MIN_SAMPLES__;

INSERT INTO platform_daily_indicator
SELECT __DATASET__,__VERSION__,d.station_code,d.observation_date,d.country,d.province,d.city,d.district,
 GREATEST(0,p.temp10-d.temp_f),GREATEST(0,d.temp_f-p.temp90),GREATEST(0,d.precip_in-p.prcp95),
 GREATEST(0,d.precip_in-p.prcp99),GREATEST(0,p.humidity5-d.humidity_ratio),
 GREATEST(0,d.max_temp_f-95),GREATEST(0,5-d.min_temp_f),
 CAST(SUBSTRING(weather_flags,1,1) AS UNSIGNED),CAST(SUBSTRING(weather_flags,2,1) AS UNSIGNED),
 CAST(SUBSTRING(weather_flags,3,1) AS UNSIGNED),CAST(SUBSTRING(weather_flags,4,1) AS UNSIGNED),
 CAST(SUBSTRING(weather_flags,5,1) AS UNSIGNED),CAST(SUBSTRING(weather_flags,6,1) AS UNSIGNED)
FROM platform_raw_observation d JOIN platform_station_threshold p
 ON p.dataset_id=d.dataset_id AND p.station_code=d.station_code AND p.result_version=__VERSION__
WHERE d.dataset_id=__DATASET__ AND d.observation_date>='1993-01-01';

INSERT INTO platform_region_daily_stat
WITH expanded AS (
 SELECT d.observation_date,l.region_level,d.country,
 CASE WHEN depth>=2 THEN d.province ELSE '' END province,CASE WHEN depth>=3 THEN d.city ELSE '' END city,
 CASE WHEN depth>=4 THEN d.district ELSE '' END district,v.metric,v.value
 FROM platform_daily_indicator d JOIN platform_indicator_value v USING(dataset_id,result_version,station_code,observation_date)
 CROSS JOIN region_levels l WHERE d.dataset_id=__DATASET__ AND d.result_version=__VERSION__
 AND v.metric IN('LTD','HTD','HR','EHR','LH','HTD1','CTD1') AND v.value IS NOT NULL
 AND d.country<>'' AND (depth<2 OR d.province<>'') AND (depth<3 OR d.city<>'')
 AND (depth<4 OR (d.country='中国' AND d.district<>''))
), ranked AS (
 SELECT *,ROW_NUMBER() OVER(PARTITION BY observation_date,region_level,country,province,city,district,metric ORDER BY value) rn,
 COUNT(*) OVER(PARTITION BY observation_date,region_level,country,province,city,district,metric) n FROM expanded
)
SELECT __DATASET__,__VERSION__,observation_date,region_level,country,province,city,district,metric,
 AVG(value),MIN(value),MAX(value),AVG(CASE WHEN rn IN(FLOOR((n+1)/2),FLOOR((n+2)/2)) THEN value END),COUNT(*)
FROM ranked GROUP BY observation_date,region_level,country,province,city,district,metric;

INSERT INTO platform_station_year_stat
SELECT __DATASET__,__VERSION__,d.station_code,YEAR(d.observation_date),v.metric,d.country,d.province,d.city,d.district,
 COUNT(CASE WHEN v.value>0 THEN 1 END),COUNT(v.value)
FROM platform_daily_indicator d JOIN platform_indicator_value v USING(dataset_id,result_version,station_code,observation_date)
WHERE d.dataset_id=__DATASET__ AND d.result_version=__VERSION__
GROUP BY d.station_code,YEAR(d.observation_date),v.metric,d.country,d.province,d.city,d.district;

INSERT INTO platform_region_year_stat
WITH expanded AS (
 SELECT s.observation_year,l.region_level,s.country,CASE WHEN depth>=2 THEN s.province ELSE '' END province,
 CASE WHEN depth>=3 THEN s.city ELSE '' END city,CASE WHEN depth>=4 THEN s.district ELSE '' END district,s.metric,s.nonzero_days
 FROM platform_station_year_stat s CROSS JOIN region_levels l WHERE s.dataset_id=__DATASET__ AND s.result_version=__VERSION__
 AND s.valid_days>0 AND s.country<>'' AND (depth<2 OR s.province<>'') AND (depth<3 OR s.city<>'')
 AND (depth<4 OR (s.country='中国' AND s.district<>''))
), ranked AS (
 SELECT *,ROW_NUMBER() OVER(PARTITION BY observation_year,region_level,country,province,city,district,metric ORDER BY nonzero_days) rn,
 COUNT(*) OVER(PARTITION BY observation_year,region_level,country,province,city,district,metric) n FROM expanded
)
SELECT __DATASET__,__VERSION__,observation_year,region_level,country,province,city,district,metric,
 AVG(nonzero_days),MIN(nonzero_days),MAX(nonzero_days),AVG(CASE WHEN rn IN(FLOOR((n+1)/2),FLOOR((n+2)/2)) THEN nonzero_days END),COUNT(*)
FROM ranked GROUP BY observation_year,region_level,country,province,city,district,metric;
