-- SYNTHETIC O6 VERIFICATION FIXTURE. These values are not live measurements or benchmark results.
-- The verifier creates #o6_operation_metrics from the migrated schema in the same session.
IF OBJECT_ID(N'tempdb..#o6_operation_metrics') IS NULL
    THROW 50000, 'Run this fixture through the O6 verifier.', 1;

INSERT INTO #o6_operation_metrics (id, category, storage, operation, latency_ms, ok, ts, size_bytes, source, scenario)
SELECT NEWID(), N'METER_READING', storage, operation, latency_ms, ok, ts, NULL, source, scenario
FROM (VALUES
    (N'DB',  N'upload',  10.0, 1, '2026-11-14T10:00:00', N'live',      N'normal'),
    (N'DB',  N'upload',  30.0, 0, '2026-11-14T10:20:00', N'live',      N'normal'),
    (N'S3',  N'upload',  40.0, 1, '2026-11-14T11:00:00', N'live',      N'peak'),
    (N'S3',  N'upload',  60.0, 1, '2026-11-14T11:10:00', N'live',      N'peak'),
    (N'S3',  N'upload',  90.0, 1, '2026-11-14T11:20:00', N'live',      N'storm'),
    (N'FTP', N'upload', 100.0, 1, '2026-11-14T10:00:00', N'benchmark', N'benchmark'),
    (N'S3',  N'upload',  20.0, 1, '2026-11-14T11:15:00', N'benchmark', N'benchmark'),
    (N'DB',  N'upload', 500.0, 1, '2026-11-14T10:10:00', N'benchmark', N'normal'),
    (N'S3',  N'upload', 600.0, 1, '2026-11-14T11:05:00', N'live',      NULL),
    (N'FTP', N'upload', 600.0, 1, '2026-11-14T11:30:00', N'live',      N'peak'),
    (N'FTP', N'upload', 600.0, 1, '2026-11-14T10:59:59', N'live',      N'peak'),
    (N'FTP', N'upload', 600.0, 1, '2026-11-14T09:59:59', N'live',      N'normal'),
    (N'S3',  N'download', 900.0, 0, '2026-11-14T10:40:00', N'live',    N'normal')
) AS fixture(storage, operation, latency_ms, ok, ts, source, scenario);

INSERT INTO #o6_operation_metrics (id, category, storage, operation, latency_ms, ok, ts, size_bytes, source, scenario)
VALUES (NEWID(), N'OUTAGE_RECORD', N'FTP', N'upload', 0, 1, '2026-11-14T10:00:00', NULL, N'live', N'normal'),
       (NEWID(), N'OUTAGE_RECORD', N'S3', N'upload',  1, 1, '2026-11-14T11:00:00', NULL, N'live', N'peak');
