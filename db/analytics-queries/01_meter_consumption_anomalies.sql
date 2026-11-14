DECLARE @to_utc datetime2(7) = SYSUTCDATETIME();
DECLARE @from_utc datetime2(7) = DATEADD(day, -1, @to_utc);
DECLARE @page int = 1;
DECLARE @page_size int = 20;

IF @from_utc IS NULL OR @to_utc IS NULL OR @from_utc >= @to_utc
    THROW 50000, 'Expected a nonempty UTC interval.', 1;
IF @page IS NULL OR @page < 1 OR @page_size IS NULL OR @page_size NOT BETWEEN 1 AND 100
    THROW 50000, 'Expected a positive page and a page size from 1 to 100.', 1;

-- Use the same materialized matches for the page and its total.
DECLARE @anomalies TABLE (
    meter_id nvarchar(128) NOT NULL,
    zone nvarchar(16) NOT NULL,
    reading_ts datetime2(7) NOT NULL,
    kwh decimal(18, 6) NOT NULL,
    median_kwh float NOT NULL,
    stddev_kwh float NOT NULL,
    threshold_kwh float NOT NULL
);

-- Include only readings whose uploads have been confirmed.
;WITH reading_statistics AS (
    SELECT
        r.meter_id,
        r.zone,
        r.reading_ts,
        r.kwh,
        PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY r.kwh)
            OVER (PARTITION BY r.meter_id) AS median_kwh,
        STDEV(r.kwh) OVER (PARTITION BY r.meter_id) AS stddev_kwh
    FROM dbo.meter_readings AS r
    INNER JOIN dbo.files AS f ON f.id = r.file_id AND f.status = N'CONFIRMED'
    WHERE r.reading_ts >= @from_utc AND r.reading_ts < @to_utc
),
thresholds AS (
    SELECT *, median_kwh + 3.0 * stddev_kwh AS threshold_kwh
    FROM reading_statistics
    WHERE stddev_kwh > 0
)
INSERT INTO @anomalies (meter_id, zone, reading_ts, kwh, median_kwh, stddev_kwh, threshold_kwh)
SELECT meter_id, zone, reading_ts, kwh, median_kwh, stddev_kwh, threshold_kwh
FROM thresholds
WHERE kwh > threshold_kwh;

DECLARE @total_items bigint = (SELECT COUNT_BIG(*) FROM @anomalies);

SELECT
    meter_id,
    zone,
    reading_ts,
    kwh,
    median_kwh,
    stddev_kwh,
    threshold_kwh,
    (kwh - median_kwh) / NULLIF(stddev_kwh, 0) AS deviations,
    @total_items AS total_items
FROM @anomalies
ORDER BY reading_ts DESC, meter_id
OFFSET (CONVERT(bigint, @page) - 1) * @page_size ROWS
FETCH NEXT @page_size ROWS ONLY;

-- O7 can read the total here even when the requested page has no rows.
SELECT @total_items AS total_items;
