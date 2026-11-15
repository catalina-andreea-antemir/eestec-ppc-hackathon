DECLARE @normal_scenario nvarchar(128) = N'normal';
DECLARE @peak_scenario nvarchar(128) = N'storm'; -- Use N'peak' for the simulator's peak profile.
DECLARE @normal_to_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @normal_from_utc datetime2(7) = DATEADD(day, -1, @normal_to_utc);
DECLARE @peak_to_utc datetime2(7) = @normal_to_utc;
DECLARE @peak_from_utc datetime2(7) = DATEADD(day, -1, @peak_to_utc);

IF NULLIF(TRIM(@normal_scenario), N'') IS NULL OR NULLIF(TRIM(@peak_scenario), N'') IS NULL
   OR @normal_scenario = @peak_scenario
    THROW 50000, 'Expected two distinct scenario labels.', 1;
IF @normal_from_utc IS NULL OR @normal_to_utc IS NULL OR @normal_from_utc >= @normal_to_utc
   OR @peak_from_utc IS NULL OR @peak_to_utc IS NULL OR @peak_from_utc >= @peak_to_utc
    THROW 50000, 'Expected a nonempty UTC interval for each scenario.', 1;

;WITH scenario_windows AS (
    SELECT
        w.run_type,
        w.scenario,
        w.from_utc,
        w.to_utc,
        CONVERT(float, DATEDIFF_BIG(second, w.from_utc, w.to_utc)
            + (DATEPART(nanosecond, w.to_utc) - DATEPART(nanosecond, w.from_utc)) / 1000000000.0)
            / 60.0 AS duration_minutes
    FROM (VALUES
        ('normal', @normal_scenario, @normal_from_utc, @normal_to_utc),
        ('peak', @peak_scenario, @peak_from_utc, @peak_to_utc)
    ) AS w(run_type, scenario, from_utc, to_utc)
),
measurements AS (
    SELECT
        m.category,
        w.run_type,
        m.ok,
        PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY m.latency_ms)
            OVER (PARTITION BY m.category, w.run_type) AS latency_p50_ms,
        PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY m.latency_ms)
            OVER (PARTITION BY m.category, w.run_type) AS latency_p95_ms,
        PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY m.latency_ms)
            OVER (PARTITION BY m.category, w.run_type) AS latency_p99_ms
    FROM dbo.operation_metrics AS m
    INNER JOIN scenario_windows AS w ON m.scenario = w.scenario
        AND m.ts >= w.from_utc AND m.ts < w.to_utc
    -- Bulkhead rejections (operation 'rejected', latency 0) are counted separately by the API.
    WHERE m.source = N'live' AND m.operation <> N'rejected'
),
scenario_stats AS (
    SELECT
        category,
        run_type,
        COUNT_BIG(*) AS operations,
        MAX(latency_p50_ms) AS latency_p50_ms,
        MAX(latency_p95_ms) AS latency_p95_ms,
        MAX(latency_p99_ms) AS latency_p99_ms,
        100.0 * COUNT_BIG(CASE WHEN ok = 0 THEN 1 END) / COUNT_BIG(*) AS error_rate_percent
    FROM measurements
    GROUP BY category, run_type
),
comparison AS (
    SELECT
        c.category,
        w.run_type,
        w.scenario,
        w.from_utc,
        w.to_utc,
        COALESCE(s.operations, 0) AS operations,
        COALESCE(s.operations, 0) / NULLIF(w.duration_minutes, 0) AS operations_per_minute,
        s.latency_p50_ms,
        s.latency_p95_ms,
        s.latency_p99_ms,
        s.error_rate_percent
    FROM (VALUES (N'METER_READING'), (N'OUTAGE_RECORD'), (N'CONNECTION_DOC'), (N'INSPECTION_VIDEO')) AS c(category)
    CROSS JOIN scenario_windows AS w
    LEFT JOIN scenario_stats AS s ON s.category = c.category AND s.run_type = w.run_type
),
baselines AS (
    SELECT *,
        FIRST_VALUE(latency_p95_ms) OVER (
            PARTITION BY category ORDER BY CASE WHEN run_type = 'normal' THEN 0 ELSE 1 END
        ) AS normal_p95_ms
    FROM comparison
)
SELECT
    category,
    run_type,
    scenario,
    from_utc,
    to_utc,
    operations,
    operations_per_minute,
    latency_p50_ms,
    latency_p95_ms,
    latency_p99_ms,
    error_rate_percent,
    CASE WHEN run_type = 'peak' THEN
        100.0 * (latency_p95_ms - normal_p95_ms) / NULLIF(normal_p95_ms, 0)
    END AS p95_change_percent
FROM baselines
ORDER BY category, CASE WHEN run_type = 'normal' THEN 0 ELSE 1 END;
