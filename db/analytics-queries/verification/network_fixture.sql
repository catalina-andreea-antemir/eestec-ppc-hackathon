-- Session-local data for the O6 verifier.
DECLARE @confirmed uniqueidentifier = NEWID();
DECLARE @failed uniqueidentifier = NEWID();

INSERT INTO #o6_files (id, category, original_filename, content_type, size_bytes, sha256,
                       storage_type, storage_key, status, encrypted, created_at, created_by)
VALUES (@confirmed, N'METER_READING', N'o6.json', N'application/json', 1, CONVERT(binary(32), 0),
        N'DB', N'o6/confirmed', N'CONFIRMED', 0, '2026-11-14', N'o6-verifier'),
       (@failed, N'METER_READING', N'o6.json', N'application/json', 1, CONVERT(binary(32), 0),
        N'DB', N'o6/failed', N'FAILED', 0, '2026-11-14', N'o6-verifier');

INSERT INTO #o6_meter_readings (meter_id, reading_ts, kwh, zone, file_id, received_at)
SELECT meter_id, DATEADD(minute, 15 * value, CONVERT(datetime2(7), '2026-11-14')),
    CASE WHEN value = 19 THEN 12 WHEN meter_id = N'even' AND value >= 10 THEN 2 ELSE 1 END,
    zone, @confirmed, '2026-11-14T04:50:00'
FROM GENERATE_SERIES(0, 19)
CROSS JOIN (VALUES (N'spike', N'Muntenia'), (N'even', N'Dobrogea')) AS meters(meter_id, zone);

INSERT INTO #o6_meter_readings (meter_id, reading_ts, kwh, zone, file_id, received_at)
SELECT meter_id, reading_ts, kwh, zone, file_id, received_at
FROM (VALUES
    -- The newest sample precedes a tied receipt of two older samples in different zones.
    (N'old',        '2026-11-13T03:00:00', 8,   N'Dobrogea', @confirmed, '2026-11-14T04:50:00'),
    (N'old',        '2026-11-13T04:00:00', 8,   N'Muntenia', @confirmed, '2026-11-14T04:50:00'),
    (N'old',        '2026-11-14T04:40:00', 1,   N'Banat',    @confirmed, '2026-11-14T04:42:00'),
    (N'old',        '2026-11-14T05:58:00', 999, N'Dobrogea', @failed,    '2026-11-14T05:58:00'),
    (N'old',        '2026-11-14T05:59:00', 999, N'Dobrogea', NULL,       '2026-11-14T05:59:00'),
    (N'old',        '2026-11-13T05:00:00', 1,   N'Muntenia', @confirmed, '2026-11-14T06:01:00'),
    (N'over',       '2026-11-14T05:00:00', 2,   N'Banat',    @confirmed, '2026-11-14T05:00:00'),
    (N'exact',      '2026-11-14T05:00:00', 3,   N'Banat',    @confirmed, '2026-11-14T05:00:00.0000001'),
    (N'active',     '2026-11-14T06:00:00', 4,   N'Banat',    @confirmed, '2026-11-14T06:00:00'),
    (N'pending',    '2026-11-14T00:00:00', 999, N'Muntenia', NULL,       '2026-11-14T06:00:00'),
    (N'flat',       '2026-11-14T04:00:00', 5,   N'Banat',    @confirmed, '2026-11-14T04:01:00'),
    (N'flat',       '2026-11-14T04:15:00', 5,   N'Banat',    @confirmed, '2026-11-14T04:15:00'),
    -- Historical samples just arrived at the reference time.
    (N'historical', '2026-11-10T08:00:00', 2,   N'Banat',    @confirmed, '2026-11-14T06:00:00.0000001'),
    (N'historical', '2026-11-10T08:15:00', 2,   N'Banat',    @confirmed, '2026-11-14T06:00:00.0000001')
) AS readings(meter_id, reading_ts, kwh, zone, file_id, received_at);

INSERT INTO #o6_outage_records (file_id, zone, station_id, occurred_at)
SELECT NEWID(), zone, station_id, occurred_at
FROM (VALUES
    (N'Muntenia', N'ST-01', '2026-11-13T08:00:00'),
    (N'Muntenia', NULL,     '2026-11-14T09:00:00'),
    (N'Dobrogea', NULL,     '2026-11-13T08:30:00'),
    (N'Dobrogea', N'ST-02', '2026-11-14T08:00:00'),
    (N'Banat',    NULL,     '2026-11-13T07:59:59'),
    (N'Banat',    NULL,     '2026-11-14T10:00:00')
) AS outages(zone, station_id, occurred_at);
