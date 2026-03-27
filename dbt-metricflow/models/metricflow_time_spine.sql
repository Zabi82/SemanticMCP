{{
  config(
    materialized='table',
    unique_key='date_day'
  )
}}

-- Generate a date spine using multiple SEQUENCE batches
-- This works around Trino's 10,000 entry limit per SEQUENCE
WITH 
-- Generate 10 years at a time (3650 days per batch)
batch1 AS (
  SELECT DATE_ADD('day', seq_num, DATE '1990-01-01') AS date_day
  FROM UNNEST(SEQUENCE(0, 3649)) AS t(seq_num)
),
batch2 AS (
  SELECT DATE_ADD('day', seq_num, DATE '2000-01-01') AS date_day
  FROM UNNEST(SEQUENCE(0, 3652)) AS t(seq_num)  -- 2000 is leap year
),
batch3 AS (
  SELECT DATE_ADD('day', seq_num, DATE '2010-01-01') AS date_day
  FROM UNNEST(SEQUENCE(0, 3649)) AS t(seq_num)
),
batch4 AS (
  SELECT DATE_ADD('day', seq_num, DATE '2020-01-01') AS date_day
  FROM UNNEST(SEQUENCE(0, 3653)) AS t(seq_num)  -- 2020 is leap year
),
batch5 AS (
  SELECT DATE_ADD('day', seq_num, DATE '2030-01-01') AS date_day
  FROM UNNEST(SEQUENCE(0, 3649)) AS t(seq_num)
),
combined AS (
  SELECT date_day FROM batch1
  UNION ALL
  SELECT date_day FROM batch2
  UNION ALL
  SELECT date_day FROM batch3
  UNION ALL
  SELECT date_day FROM batch4
  UNION ALL
  SELECT date_day FROM batch5
)

SELECT
  date_day,
  YEAR(date_day) AS year,
  MONTH(date_day) AS month,
  DAY(date_day) AS day,
  QUARTER(date_day) AS quarter,
  DAY_OF_WEEK(date_day) AS day_of_week,
  DAY_OF_YEAR(date_day) AS day_of_year,
  WEEK(date_day) AS week_of_year
FROM combined
WHERE date_day < DATE '2040-01-01'  -- Cap at 2040
ORDER BY date_day
