-- Run after sample import. Same business query, with and without the primary index.
EXPLAIN ANALYZE
SELECT observation_date,ltd,htd,hr FROM daily_indicator
WHERE station_code='57328099999' AND observation_date>='1993-01-01' AND observation_date<'1994-01-01'
ORDER BY observation_date LIMIT 100;

EXPLAIN ANALYZE
SELECT observation_date,ltd,htd,hr FROM daily_indicator IGNORE INDEX(PRIMARY,idx_daily_date,idx_daily_region)
WHERE station_code='57328099999' AND observation_date>='1993-01-01' AND observation_date<'1994-01-01'
ORDER BY observation_date LIMIT 100;
