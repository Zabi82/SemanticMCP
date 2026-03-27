# dbt Metricflow Deep Dive

This document explains how dbt metricflow works using your actual semantic models and metrics from the TPCH demo.

## Core Concepts

### 1. Semantic Models
Semantic models are the foundation. They define how to interpret your data tables.

**Your semantic models:**
- `customer` - Customer master data
- `orders` - Order transactions
- `lineitems` - Individual line items within orders

Each semantic model has four key components:

#### A. Model Reference
Points to a dbt model (view or table):
```yaml
model: ref('stg_customer')
```

#### B. Entities (The Join Keys)
Entities define relationships between semantic models:

**Primary entity** - The unique identifier for this model:
```yaml
entities:
  - name: customer_id
    type: primary
    expr: custkey  # Maps to the actual column name
```

**Foreign entity** - Links to another semantic model:
```yaml
entities:
  - name: customer_id
    type: foreign
    expr: custkey  # In orders model, links to customer
```

**Your entity graph:**
```
customer (customer_id) ←─── orders (customer_id, order_id) ←─── lineitems (order_id)
```

#### C. Dimensions (Group By / Filter)
Dimensions are attributes you can group by or filter on:

**Categorical dimensions:**
```yaml
dimensions:
  - name: market_segment
    type: categorical
    expr: market_segment
```

**Time dimensions:**
```yaml
dimensions:
  - name: order_date
    type: time
    expr: orderdate
    type_params:
      time_granularity: day
```

#### D. Measures (Aggregations)
Measures are the raw calculations that get aggregated:

```yaml
measures:
  - name: net_revenue
    agg: sum
    expr: extendedprice * (1 - discount)
    agg_time_dimension: ship_date  # REQUIRED for time-series
```

**Common aggregations:**
- `sum` - Add up values
- `count` - Count rows
- `count_distinct` - Count unique values
- `avg` - Average
- `min` / `max` - Min/max values

### 2. Metrics
Metrics are the business-friendly layer on top of measures.

#### Type 1: Simple Metrics
Direct reference to a measure:

```yaml
- name: revenue
  type: simple
  type_params:
    measure: net_revenue  # References the measure from lineitems
```

**Generated SQL:**
```sql
SELECT
  SUM(extendedprice * (1 - discount)) AS revenue
FROM stg_lineitems
```

#### Type 2: Derived Metrics
Calculated from other metrics:

```yaml
- name: average_order_value
  type: derived
  type_params:
    expr: revenue / order_volume
    metrics:
      - revenue
      - order_volume
```

**Generated SQL:**
```sql
SELECT
  revenue / order_volume AS average_order_value
FROM (
  SELECT
    MAX(subq_4.revenue) AS revenue,
    MAX(subq_9.order_volume) AS order_volume
  FROM (
    SELECT SUM(extendedprice * (1 - discount)) AS revenue
    FROM stg_lineitems
  ) subq_4
  CROSS JOIN (
    SELECT SUM(CASE WHEN orderkey IS NOT NULL THEN 1 ELSE 0 END) AS order_volume
    FROM stg_orders
  ) subq_9
) subq_10
```

#### Type 3: Cumulative Metrics
Running totals over time:

```yaml
- name: cumulative_revenue
  type: cumulative
  type_params:
    measure: net_revenue
```

This creates a window function that sums revenue over time.

#### Type 4: Metrics with Filters
Pre-filtered metrics:

```yaml
- name: premium_customer_revenue
  type: simple
  type_params:
    measure: net_revenue
  filter: |
    {{ Dimension('customer_id__market_segment') }} IN ('AUTOMOBILE', 'BUILDING')
```

**Generated SQL:**
```sql
SELECT
  SUM(extendedprice * (1 - discount)) AS premium_customer_revenue
FROM stg_lineitems
JOIN stg_orders ON lineitems.orderkey = orders.orderkey
JOIN stg_customer ON orders.custkey = customer.custkey
WHERE customer.market_segment IN ('AUTOMOBILE', 'BUILDING')
```

## How Joins Work

### Dimension Paths
When you query a metric with dimensions from other tables, metricflow follows the entity relationships:

**Example: Revenue by customer market segment**

Query:
```bash
mf query --metrics revenue --group-by order_id__customer_id__market_segment
```

The dimension path `order_id__customer_id__market_segment` means:
1. Start at `lineitems` (where revenue measure lives)
2. Join to `orders` via `order_id` entity
3. Join to `customer` via `customer_id` entity
4. Access `market_segment` dimension

**Generated SQL:**
```sql
SELECT
  customer.market_segment AS order_id__customer_id__market_segment,
  SUM(lineitems.extendedprice * (1 - lineitems.discount)) AS revenue
FROM stg_lineitems lineitems
LEFT OUTER JOIN stg_orders orders
  ON lineitems.orderkey = orders.orderkey
LEFT OUTER JOIN stg_customer customer
  ON orders.custkey = customer.custkey
GROUP BY customer.market_segment
```

### Why Dimension Paths Matter
You can't just use `market_segment` - you must specify the full path because:
1. Multiple semantic models might have dimensions with the same name
2. There might be multiple join paths to reach a dimension
3. Metricflow needs to know which entities to traverse

## Time Dimensions

### agg_time_dimension (Required)
Every measure MUST have an `agg_time_dimension`:

```yaml
measures:
  - name: net_revenue
    agg: sum
    expr: extendedprice * (1 - discount)
    agg_time_dimension: ship_date  # Links to the ship_date dimension
```

This tells metricflow:
- Which time dimension to use for time-series queries
- How to handle time-based filtering
- Which dimension to use for cumulative metrics

### metric_time (Special Dimension)
When you query a metric, metricflow automatically creates a `metric_time` dimension that maps to the measure's `agg_time_dimension`.

**Example:**
```bash
mf query --metrics revenue --group-by metric_time__month
```

This groups revenue by month using the `ship_date` from lineitems.

## Practical Examples from Your Setup

### Example 1: Simple Metric Query
```bash
mf query --metrics revenue
```

**What happens:**
1. Metricflow looks up the `revenue` metric
2. Finds it references the `net_revenue` measure from `lineitems` semantic model
3. Generates SQL: `SELECT SUM(extendedprice * (1 - discount)) FROM stg_lineitems`
4. Returns: `2,045,130,000.00`

### Example 2: Metric with Dimension
```bash
mf query --metrics revenue --group-by order_id__customer_id__market_segment
```

**What happens:**
1. Metricflow starts at `lineitems` (where revenue lives)
2. Sees you want `market_segment` which is in `customer`
3. Follows the entity path: lineitems → orders → customer
4. Generates JOINs automatically
5. Groups by market_segment

### Example 3: Derived Metric
```bash
mf query --metrics average_order_value
```

**What happens:**
1. Metricflow sees this is a derived metric: `revenue / order_volume`
2. Calculates `revenue` from lineitems
3. Calculates `order_volume` from orders
4. Uses CROSS JOIN to combine them (since no common dimensions)
5. Divides revenue by order_volume

### Example 4: Multiple Metrics
```bash
mf query --metrics revenue,order_volume --group-by metric_time__month
```

**What happens:**
1. Metricflow calculates both metrics
2. Groups both by month
3. Generates a single query with both aggregations

## Common Patterns

### Pattern 1: Count Distinct for Entities
```yaml
measures:
  - name: customer_count
    agg: count_distinct
    expr: custkey
```

Use `count_distinct` when counting unique entities (customers, orders, products).

### Pattern 2: Calculated Measures
```yaml
measures:
  - name: net_revenue
    agg: sum
    expr: extendedprice * (1 - discount)  # Calculation happens before aggregation
```

The expression is evaluated row-by-row, then aggregated.

### Pattern 3: Time Granularity
```yaml
- name: monthly_revenue
  type: simple
  type_params:
    measure: net_revenue
  time_granularity: month
```

This automatically groups by month when queried.

### Pattern 4: Auto-Create Metrics
```yaml
measures:
  - name: net_revenue
    agg: sum
    expr: extendedprice * (1 - discount)
    agg_time_dimension: ship_date
    create_metric: true  # Automatically creates a simple metric
```

This creates a metric with the same name as the measure.

## Limitations You Encountered

### Cross-Semantic-Model Ratios
```yaml
# This DOESN'T work well:
- name: revenue_per_customer
  type: ratio
  type_params:
    numerator: revenue      # From lineitems
    denominator: customer_count  # From customer
```

**Why it fails:**
- Ratio metrics have limited support for cross-model calculations
- The join path from lineitems → customer is complex
- Metricflow can't always determine the correct aggregation level

**Workaround:**
Use derived metrics instead:
```yaml
- name: revenue_per_customer
  type: derived
  type_params:
    expr: revenue / customer_count
    metrics:
      - revenue
      - customer_count
```

But this still has issues if the metrics come from different grain levels.

## Best Practices

1. **Name entities consistently** - Use the same entity name across semantic models for joins
2. **Always set agg_time_dimension** - Required for time-series queries
3. **Use descriptive dimension paths** - `order_id__customer_id__market_segment` is clear
4. **Test metrics with --explain** - See the generated SQL to understand what's happening
5. **Keep measures simple** - Complex calculations should be in derived metrics, not measures
6. **Use create_metric: true** - For common measures that should be directly queryable

## Testing Your Metrics

### List all metrics:
```bash
docker exec dbt-metricflow mf list metrics
```

### Query a metric:
```bash
docker exec dbt-metricflow mf query --metrics revenue
```

### See generated SQL:
```bash
docker exec dbt-metricflow mf query --metrics revenue --explain
```

### Query with dimensions:
```bash
docker exec dbt-metricflow mf query --metrics revenue --group-by order_id__customer_id__market_segment
```

### List dimensions for a metric:
```bash
docker exec dbt-metricflow mf list dimensions --metrics revenue
```

## Summary

**Semantic models** define your data structure (entities, dimensions, measures)
↓
**Measures** define raw aggregations (SUM, COUNT, AVG)
↓
**Metrics** provide business-friendly names and calculations
↓
**Metricflow** generates SQL by following entity relationships
↓
**Your API** exposes metrics to agents via MCP tools

The power of metricflow is that it:
- Automatically handles JOINs based on entity relationships
- Provides consistent metric definitions
- Generates optimized SQL
- Makes metrics discoverable and self-documenting


## MetricFlow Time Spine

### What is it?
The `metricflow_time_spine` is a special table that contains a continuous sequence of dates. Your time spine has:
- **18,257 rows** (50 years of daily dates from 1990 to 2040)
- **Columns**: date_day, year, month, day, quarter, day_of_week, day_of_year, week_of_year

### Why do you need it?

#### Use Case 1: Cumulative Metrics
When you query cumulative metrics, metricflow needs to calculate running totals. The time spine provides the date sequence.

**Example: Cumulative Revenue**
```bash
mf query --metrics cumulative_revenue --group-by metric_time --order metric_time
```

**Generated SQL:**
```sql
SELECT
  time_spine.date_day AS metric_time__day,
  SUM(lineitems.revenue) AS cumulative_revenue
FROM metricflow_time_spine time_spine
INNER JOIN (
  SELECT
    DATE_TRUNC('day', shipdate) AS metric_time__day,
    extendedprice * (1 - discount) AS revenue
  FROM stg_lineitems
) lineitems
ON (lineitems.metric_time__day <= time_spine.date_day)  -- Key: <= for cumulative
GROUP BY time_spine.date_day
ORDER BY metric_time__day
```

**How it works:**
1. For each date in the time spine (e.g., 1992-01-15)
2. Sum ALL revenue where ship_date <= 1992-01-15
3. This creates a running total

**Without time spine:** You'd only get dates where there's actual data, missing dates with no transactions.

#### Use Case 2: Filling Gaps in Time Series
When you want a complete time series even for dates with no data:

**Example: Daily revenue for last 30 days**
```bash
mf query --metrics revenue --group-by metric_time --where "metric_time >= '2024-01-01'"
```

With time spine, you get:
```
2024-01-01 | 1000
2024-01-02 | 0      ← No transactions, but date is included
2024-01-03 | 2500
2024-01-04 | 0      ← No transactions, but date is included
```

Without time spine, you'd only get dates with transactions:
```
2024-01-01 | 1000
2024-01-03 | 2500   ← Missing 2024-01-02 and 2024-01-04
```

#### Use Case 3: Window Functions
For metrics that use window functions (moving averages, period-over-period comparisons), the time spine ensures consistent date ranges.

### Your Time Spine Configuration

**SQL Model** (`metricflow_time_spine.sql`):
```sql
-- Generates dates from 1990 to 2040
-- Uses multiple SEQUENCE batches to work around Trino's 10,000 entry limit
SELECT
  date_day,
  YEAR(date_day) AS year,
  MONTH(date_day) AS month,
  -- ... other date parts
FROM combined_batches
WHERE date_day < DATE '2040-01-01'
```

**Semantic Model** (`metricflow_time_spine.yml`):
```yaml
semantic_models:
  - name: metricflow_time_spine
    model: ref('metricflow_time_spine')
    
    entities:
      - name: ds           # Special entity name for time spine
        type: primary
        expr: date_day
    
    dimensions:
      - name: date_day
        type: time
        type_params:
          time_granularity: day
```

### When is it used?
- **Cumulative metrics** - Always uses time spine
- **Regular time-series** - Only if you explicitly request gap-filling
- **Window functions** - Uses time spine for consistent windows
- **Period comparisons** - Uses time spine to align periods

### When is it NOT used?
- Simple aggregations without time grouping
- Time-series queries that don't need gap-filling
- Metrics without time dimensions

### Best Practices

1. **Date Range**: Ensure your time spine covers your data range
   - Your spine: 1990-2040 (50 years)
   - Your TPCH data: 1992-1998
   - ✅ Good coverage

2. **Granularity**: Match your finest time granularity
   - Your spine: Daily
   - Your metrics: Daily (ship_date, order_date)
   - ✅ Matches

3. **Performance**: Time spine adds overhead for cumulative queries
   - 18,257 rows is reasonable
   - Consider filtering date ranges in queries

4. **Materialization**: Always materialize as a table (not view)
   - Your config: `materialized='table'` ✅
   - Reason: Frequently joined, needs to be fast

### Verifying Your Time Spine

Check if it exists:
```bash
docker exec trino trino --catalog semantic_demo --schema ice_db \
  --execute "SELECT MIN(date_day), MAX(date_day), COUNT(*) FROM metricflow_time_spine"
```

Expected output:
```
1990-01-01 | 2039-12-31 | 18,257
```

### Common Issues

**Issue 1: Table not found**
```
ERROR: Table 'metricflow_time_spine' does not exist
```
**Solution:**
```bash
docker exec dbt-metricflow dbt run --select metricflow_time_spine
```

**Issue 2: Date range too small**
```
ERROR: No time spine entries for date range
```
**Solution:** Extend the date range in `metricflow_time_spine.sql`

**Issue 3: Performance issues with cumulative metrics**
```
Query takes too long
```
**Solution:** Add WHERE clause to limit date range:
```bash
mf query --metrics cumulative_revenue --where "metric_time >= '1995-01-01'"
```

### Summary

The time spine is like a calendar table that ensures:
- ✅ Complete date sequences (no gaps)
- ✅ Cumulative calculations work correctly
- ✅ Time-series analysis is consistent
- ✅ Window functions have proper date ranges

Without it, cumulative metrics and gap-filled time series won't work properly.
