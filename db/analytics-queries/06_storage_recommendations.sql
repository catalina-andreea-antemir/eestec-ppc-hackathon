DECLARE @to_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @from_utc datetime2(7) = DATEADD(day, -30, @to_utc);
DECLARE @operation nvarchar(64) = N'upload';

-- Defaults from storage-costs.yml. Override these together when evaluating a different cost assumption.
DECLARE @ftp_cost_per_gb decimal(9, 4) = 1.0;
DECLARE @s3_cost_per_gb decimal(9, 4) = 1.4;
DECLARE @db_cost_per_gb decimal(9, 4) = 3.2;

IF @from_utc IS NULL OR @to_utc IS NULL OR @from_utc >= @to_utc
    THROW 50000, 'Expected a nonempty UTC interval.', 1;
IF NULLIF(TRIM(@operation), N'') IS NULL
    THROW 50000, 'Expected an operation label.', 1;
IF @ftp_cost_per_gb IS NULL OR @ftp_cost_per_gb <= 0
   OR @s3_cost_per_gb IS NULL OR @s3_cost_per_gb <= 0
   OR @db_cost_per_gb IS NULL OR @db_cost_per_gb <= 0
    THROW 50000, 'Expected a positive cost for each storage.', 1;

;WITH storage_costs AS (
    SELECT storage, relative_cost_per_gb, MIN(relative_cost_per_gb) OVER () AS cheapest_cost_per_gb
    FROM (VALUES
        (N'FTP', @ftp_cost_per_gb), (N'S3', @s3_cost_per_gb), (N'DB', @db_cost_per_gb)
    ) AS s(storage, relative_cost_per_gb)
),
measurements AS (
    SELECT
        category,
        storage,
        source,
        ok,
        PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY latency_ms)
            OVER (PARTITION BY category, storage) AS latency_p50_ms,
        PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms)
            OVER (PARTITION BY category, storage) AS latency_p95_ms
    FROM dbo.operation_metrics
    WHERE ts >= @from_utc AND ts < @to_utc AND operation = @operation
      AND source IN (N'benchmark', N'live')
),
storage_stats AS (
    SELECT
        category,
        storage,
        COUNT_BIG(*) AS samples,
        COUNT_BIG(CASE WHEN source = N'benchmark' THEN 1 END) AS benchmark_samples,
        COUNT_BIG(CASE WHEN source = N'live' THEN 1 END) AS live_samples,
        MAX(latency_p50_ms) AS latency_p50_ms,
        MAX(latency_p95_ms) AS latency_p95_ms,
        CONVERT(float, COUNT_BIG(CASE WHEN ok = 0 THEN 1 END)) / COUNT_BIG(*) AS failure_rate
    FROM measurements
    GROUP BY category, storage
),
score_inputs AS (
    SELECT
        c.category,
        costs.storage,
        costs.relative_cost_per_gb,
        costs.cheapest_cost_per_gb,
        COALESCE(s.samples, 0) AS samples,
        COALESCE(s.benchmark_samples, 0) AS benchmark_samples,
        COALESCE(s.live_samples, 0) AS live_samples,
        s.latency_p50_ms,
        s.latency_p95_ms,
        s.failure_rate,
        MIN(s.latency_p95_ms) OVER (PARTITION BY c.category) AS best_p95_ms
    FROM (VALUES (N'METER_READING'), (N'OUTAGE_RECORD'), (N'CONNECTION_DOC'), (N'INSPECTION_VIDEO')) AS c(category)
    CROSS JOIN storage_costs AS costs
    LEFT JOIN storage_stats AS s ON s.category = c.category AND s.storage = costs.storage
),
scores AS (
    SELECT *,
        -- The 1 ms offset keeps the latency ratio defined when the fastest p95 is zero.
        50.0 * (best_p95_ms + 1.0) / (latency_p95_ms + 1.0)
            + 35.0 * (1.0 - failure_rate)
            + 15.0 * cheapest_cost_per_gb / relative_cost_per_gb AS score
    FROM score_inputs
)
SELECT
    category,
    storage,
    @operation AS operation,
    CONVERT(decimal(6, 3), score) AS score,
    CASE WHEN score IS NOT NULL THEN DENSE_RANK() OVER (PARTITION BY category ORDER BY score DESC) END AS [rank],
    latency_p50_ms,
    latency_p95_ms,
    100.0 * failure_rate AS error_rate_percent,
    relative_cost_per_gb,
    samples,
    benchmark_samples,
    live_samples,
    CONVERT(bit, CASE WHEN benchmark_samples > 0 THEN 1 ELSE 0 END) AS benchmarked
FROM scores
ORDER BY category, score DESC, storage;
