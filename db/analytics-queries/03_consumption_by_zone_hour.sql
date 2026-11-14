DECLARE @to_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @from_utc datetime2(7) = DATEADD(day, -1, @to_utc);

IF @from_utc IS NULL OR @to_utc IS NULL OR @from_utc >= @to_utc
    THROW 50000, 'Expected a nonempty UTC interval.', 1;

SELECT
    r.zone,
    DATETRUNC(hour, r.reading_ts) AS hour_utc,
    COUNT_BIG(*) AS readings,
    SUM(r.kwh) AS total_kwh
FROM dbo.meter_readings AS r
INNER JOIN dbo.files AS f ON f.id = r.file_id AND f.status = N'CONFIRMED'
WHERE r.reading_ts >= @from_utc AND r.reading_ts < @to_utc
GROUP BY r.zone, DATETRUNC(hour, r.reading_ts)
ORDER BY hour_utc, r.zone;
