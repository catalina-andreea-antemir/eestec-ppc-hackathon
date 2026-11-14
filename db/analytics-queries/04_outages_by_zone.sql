DECLARE @to_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @from_utc datetime2(7) = DATEADD(day, -30, @to_utc);

IF @from_utc IS NULL OR @to_utc IS NULL OR @from_utc >= @to_utc
    THROW 50000, 'Expected a nonempty UTC interval.', 1;

-- First result set: rank zones within the selected interval.
;WITH zone_hours AS (
    SELECT zone, DATEPART(hour, occurred_at) AS hour_utc, COUNT_BIG(*) AS outages
    FROM dbo.outage_records
    WHERE occurred_at >= @from_utc AND occurred_at < @to_utc
    GROUP BY zone, DATEPART(hour, occurred_at)
),
peak_hours AS (
    SELECT *,
        FIRST_VALUE(hour_utc) OVER (PARTITION BY zone ORDER BY outages DESC, hour_utc) AS peak_hour_utc
    FROM zone_hours
),
zone_totals AS (
    SELECT zone, SUM(outages) AS outages, MAX(peak_hour_utc) AS peak_hour_utc
    FROM peak_hours
    GROUP BY zone
),
all_zones AS (
    SELECT
        z.zone,
        COALESCE(t.outages, 0) AS outages,
        t.peak_hour_utc,
        SUM(COALESCE(t.outages, 0)) OVER () AS total_outages,
        COUNT_BIG(*) OVER () AS zone_count
    FROM (VALUES (N'Muntenia'), (N'Banat'), (N'Dobrogea')) AS z(zone)
    LEFT JOIN zone_totals AS t ON t.zone = z.zone
)
SELECT
    DENSE_RANK() OVER (ORDER BY outages DESC) AS [rank],
    zone,
    outages,
    total_outages,
    100.0 * outages / NULLIF(total_outages, 0) AS share_percent,
    1.0 * outages * zone_count / NULLIF(total_outages, 0) AS vs_average_ratio,
    peak_hour_utc
FROM all_zones
ORDER BY [rank], zone;

-- Second result set: UTC hour-of-day distribution across the same interval.
SELECT zone, DATEPART(hour, occurred_at) AS hour_utc, COUNT_BIG(*) AS outages
FROM dbo.outage_records
WHERE occurred_at >= @from_utc AND occurred_at < @to_utc
GROUP BY zone, DATEPART(hour, occurred_at)
ORDER BY zone, hour_utc;
