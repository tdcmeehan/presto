-- ============================================================
-- Dynamic Partition Pruning Demo
-- ============================================================
--
-- Prerequisites:
--   1. Build:  ./mvnw install -DskipTests -Dair.check.skip-all=true \
--              -pl presto-common,presto-spi,presto-main-base,presto-main,presto-iceberg -am -T 1C
--   2. Start IcebergQueryRunner.main() from your IDE.
--   3. Connect: presto --server localhost:8080 --catalog iceberg
--   4. Paste these statements into the CLI.
--

-- ============================================================
-- SETUP
-- ============================================================

CREATE SCHEMA IF NOT EXISTS iceberg.dpp_demo;
USE iceberg.dpp_demo;

-- Fact table: 10 partitions (customer_id 1..10), 10 rows each
-- Each customer_id gets its own partition directory and data file.
CREATE TABLE fact_orders
WITH (partitioning = ARRAY['customer_id']) AS
SELECT
    CAST(c.customer_id * 100 + r.row_num AS BIGINT) AS order_id,
    c.customer_id,
    CAST(c.customer_id * 100 + r.row_num AS DECIMAL(10, 2)) AS amount,
    DATE '2024-01-01' + INTERVAL '1' DAY * c.customer_id AS order_date
FROM
    UNNEST(sequence(1, 10)) AS c(customer_id)
CROSS JOIN
    UNNEST(sequence(1, 10)) AS r(row_num);

-- Dimension table: 10 customers, 3 in WEST region
CREATE TABLE dim_customers AS
SELECT
    CAST(n AS BIGINT) AS customer_id,
    'Customer ' || CAST(n AS VARCHAR) AS customer_name,
    CASE WHEN n <= 3 THEN 'WEST' ELSE 'EAST' END AS region
FROM UNNEST(sequence(1, 10)) AS t(n);

-- Verify data
SELECT 'fact_orders' AS tbl, COUNT(*) AS rows, COUNT(DISTINCT customer_id) AS partitions FROM fact_orders
UNION ALL
SELECT 'dim_customers', COUNT(*), COUNT(DISTINCT customer_id) FROM dim_customers;

SELECT region, COUNT(*) AS customers FROM dim_customers GROUP BY region;

-- ============================================================
-- QUERY 1: Selective partition filter (3/10 partitions)
-- Look for dynamicFilterSplitsProcessed and skippedDataManifests
-- in the EXPLAIN ANALYZE output.
-- ============================================================

-- Without DPP
SET SESSION distributed_dynamic_filter_strategy = 'DISABLED';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
WHERE c.region = 'WEST'
ORDER BY f.order_id;

-- With DPP
SET SESSION distributed_dynamic_filter_strategy = 'ALWAYS';
SET SESSION distributed_dynamic_filter_max_wait_time = '5s';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
WHERE c.region = 'WEST'
ORDER BY f.order_id;

-- ============================================================
-- QUERY 2: Empty build side (all partitions pruned)
-- ============================================================

SET SESSION distributed_dynamic_filter_strategy = 'DISABLED';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
WHERE c.region = 'NONEXISTENT'
ORDER BY f.order_id;

SET SESSION distributed_dynamic_filter_strategy = 'ALWAYS';
SET SESSION distributed_dynamic_filter_max_wait_time = '5s';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
WHERE c.region = 'NONEXISTENT'
ORDER BY f.order_id;

-- ============================================================
-- QUERY 3: Semi-join (IN subquery)
-- ============================================================

SET SESSION distributed_dynamic_filter_strategy = 'DISABLED';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount
FROM fact_orders f
WHERE f.customer_id IN (
    SELECT customer_id FROM dim_customers WHERE region = 'WEST'
)
ORDER BY f.order_id;

SET SESSION distributed_dynamic_filter_strategy = 'ALWAYS';
SET SESSION distributed_dynamic_filter_max_wait_time = '5s';

EXPLAIN ANALYZE
SELECT f.order_id, f.amount
FROM fact_orders f
WHERE f.customer_id IN (
    SELECT customer_id FROM dim_customers WHERE region = 'WEST'
)
ORDER BY f.order_id;

-- ============================================================
-- CLEANUP
-- ============================================================
-- DROP TABLE IF EXISTS fact_orders;
-- DROP TABLE IF EXISTS dim_customers;
-- DROP SCHEMA IF EXISTS iceberg.dpp_demo;
