DECLARE @as_of_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @silent_minutes int = 60;

IF @as_of_utc IS NULL OR @silent_minutes IS NULL OR @silent_minutes < 1
    THROW 50000, 'Expected a UTC reference time and a positive inactivity threshold.', 1;

-- The newest reading supplies the API timestamp and zone, even when older samples arrive later.
-- A grouped aggregate (instead of per-row window functions) keeps the full-history scan parallel and sort-free;
-- (meter_id, reading_ts) is unique, so the newest reading's zone is read back through the clustered key.
;WITH latest_readings AS (
    SELECT
        r.meter_id,
        MAX(r.reading_ts) AS reading_ts,
        MAX(r.received_at) AS last_received_at
    FROM dbo.meter_readings AS r
    INNER JOIN dbo.files AS f ON f.id = r.file_id AND f.status = N'CONFIRMED'
    WHERE r.received_at <= @as_of_utc
    GROUP BY r.meter_id
),
inactivity AS (
    SELECT
        l.meter_id,
        r.zone,
        l.reading_ts,
        l.last_received_at,
        -- Restore the fractional part lost when counting crossed second boundaries.
        DATEDIFF_BIG(second, l.last_received_at, @as_of_utc)
            + (DATEPART(nanosecond, @as_of_utc) - DATEPART(nanosecond, l.last_received_at)) / 1000000000.0
            AS elapsed_seconds
    FROM latest_readings AS l
    INNER JOIN dbo.meter_readings AS r ON r.meter_id = l.meter_id AND r.reading_ts = l.reading_ts
)
SELECT
    meter_id,
    zone,
    reading_ts AS last_reading_ts,
    CONVERT(bigint, FLOOR(elapsed_seconds / 60.0)) AS silent_minutes,
    last_received_at
FROM inactivity
WHERE elapsed_seconds > @silent_minutes * 60.0
ORDER BY last_received_at, meter_id;
