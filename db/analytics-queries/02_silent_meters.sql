DECLARE @as_of_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @silent_minutes int = 60;

IF @as_of_utc IS NULL OR @silent_minutes IS NULL OR @silent_minutes < 1
    THROW 50000, 'Expected a UTC reference time and a positive inactivity threshold.', 1;

-- The newest reading supplies the API timestamp and zone, even when older samples arrive later.
;WITH latest_readings AS (
    SELECT
        r.meter_id,
        r.zone,
        r.reading_ts,
        MAX(r.received_at) OVER (PARTITION BY r.meter_id) AS last_received_at,
        ROW_NUMBER() OVER (PARTITION BY r.meter_id ORDER BY r.reading_ts DESC) AS reading_number
    FROM dbo.meter_readings AS r
    INNER JOIN dbo.files AS f ON f.id = r.file_id AND f.status = N'CONFIRMED'
    WHERE r.received_at <= @as_of_utc
),
inactivity AS (
    SELECT
        meter_id,
        zone,
        reading_ts,
        last_received_at,
        -- Restore the fractional part lost when counting crossed second boundaries.
        DATEDIFF_BIG(second, last_received_at, @as_of_utc)
            + (DATEPART(nanosecond, @as_of_utc) - DATEPART(nanosecond, last_received_at)) / 1000000000.0
            AS elapsed_seconds
    FROM latest_readings
    WHERE reading_number = 1
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
