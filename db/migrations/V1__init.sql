-- Flyway se conectează direct la baza FileGridMeta.
IF DB_NAME() <> N'FileGridMeta'
BEGIN
    THROW 50001, N'Migrarea V1 trebuie rulată în FileGridMeta.', 1;
END;

CREATE TABLE dbo.files (
    id UNIQUEIDENTIFIER NOT NULL,
    category NVARCHAR(32) NOT NULL,
    original_filename NVARCHAR(512) NOT NULL,
    content_type NVARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARBINARY(32) NOT NULL,
    sha256_plain VARBINARY(32) NULL,
    storage_type NVARCHAR(8) NOT NULL,
    storage_key NVARCHAR(1024) NOT NULL,
    status NVARCHAR(16) NOT NULL CONSTRAINT DF_files_status DEFAULT (N'PENDING'),
    encrypted BIT NOT NULL CONSTRAINT DF_files_encrypted DEFAULT (0),
    created_at DATETIME2(7) NOT NULL CONSTRAINT DF_files_created_at DEFAULT (SYSUTCDATETIME()),
    created_by NVARCHAR(128) NOT NULL,

    CONSTRAINT PK_files PRIMARY KEY (id),
    CONSTRAINT CK_files_category CHECK (
        category IN (N'METER_READING', N'OUTAGE_RECORD', N'CONNECTION_DOC', N'INSPECTION_VIDEO')
    ),
    CONSTRAINT CK_files_storage_type CHECK (storage_type IN (N'FTP', N'S3', N'DB')),
    CONSTRAINT CK_files_status CHECK (status IN (N'PENDING', N'CONFIRMED', N'FAILED')),
    CONSTRAINT CK_files_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT CK_files_sha256 CHECK (DATALENGTH(sha256) = 32),
    CONSTRAINT CK_files_sha256_plain CHECK (
        sha256_plain IS NULL
        OR (DATALENGTH(sha256_plain) = 32 AND category = N'CONNECTION_DOC' AND encrypted = 1)
    )
);

CREATE NONCLUSTERED INDEX IX_files_category_created_at ON dbo.files (category, created_at);
CREATE NONCLUSTERED INDEX IX_files_created_at ON dbo.files (created_at);
CREATE NONCLUSTERED INDEX IX_files_original_filename ON dbo.files (original_filename);

CREATE TABLE dbo.upload_sessions (
    upload_id UNIQUEIDENTIFIER NOT NULL,
    category NVARCHAR(32) NOT NULL,
    storage_type NVARCHAR(8) NOT NULL,
    storage_upload_ref NVARCHAR(1024) NOT NULL,
    original_filename NVARCHAR(512) NOT NULL,
    content_type NVARCHAR(255) NOT NULL
        CONSTRAINT DF_upload_sessions_content_type DEFAULT (N'application/octet-stream'),
    chunk_size INT NOT NULL,
    total_size BIGINT NOT NULL,
    status NVARCHAR(16) NOT NULL CONSTRAINT DF_upload_sessions_status DEFAULT (N'PENDING'),
    next_chunk INT NOT NULL CONSTRAINT DF_upload_sessions_next_chunk DEFAULT (1),
    lease_owner NVARCHAR(128) NULL,
    lease_until DATETIME2(7) NULL,
    created_at DATETIME2(7) NOT NULL
        CONSTRAINT DF_upload_sessions_created_at DEFAULT (SYSUTCDATETIME()),
    -- Aplicația actualizează updated_at la progresul sesiunii.
    updated_at DATETIME2(7) NOT NULL
        CONSTRAINT DF_upload_sessions_updated_at DEFAULT (SYSUTCDATETIME()),
    storage_unavailable_since DATETIME2(7) NULL,
    created_by NVARCHAR(128) NOT NULL,

    CONSTRAINT PK_upload_sessions PRIMARY KEY (upload_id),
    CONSTRAINT CK_upload_sessions_category CHECK (
        category IN (N'METER_READING', N'OUTAGE_RECORD', N'CONNECTION_DOC', N'INSPECTION_VIDEO')
    ),
    CONSTRAINT CK_upload_sessions_storage_type CHECK (storage_type IN (N'FTP', N'S3', N'DB')),
    CONSTRAINT CK_upload_sessions_status CHECK (
        status IN (N'PENDING', N'COMPLETED', N'ABORTED', N'FAILED')
    ),
    -- Dimensiunea nominală a unei bucăți este între 5 și 64 MiB.
    CONSTRAINT CK_upload_sessions_chunk_size CHECK (chunk_size BETWEEN 5242880 AND 67108864),
    CONSTRAINT CK_upload_sessions_total_size CHECK (total_size > 0),
    CONSTRAINT CK_upload_sessions_next_chunk CHECK (next_chunk >= 1),
    CONSTRAINT CK_upload_sessions_lease CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    )
);

CREATE NONCLUSTERED INDEX IX_upload_sessions_status_updated_at
    ON dbo.upload_sessions (status, updated_at);

CREATE TABLE dbo.upload_chunks (
    upload_id UNIQUEIDENTIFIER NOT NULL,
    n INT NOT NULL,
    size_bytes INT NOT NULL,
    sha256 VARBINARY(32) NOT NULL,
    etag NVARCHAR(255) NULL,

    CONSTRAINT PK_upload_chunks PRIMARY KEY (upload_id, n),
    CONSTRAINT FK_upload_chunks_upload_sessions FOREIGN KEY (upload_id)
        REFERENCES dbo.upload_sessions (upload_id),
    CONSTRAINT CK_upload_chunks_n CHECK (n >= 1),
    CONSTRAINT CK_upload_chunks_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT CK_upload_chunks_sha256 CHECK (DATALENGTH(sha256) = 32)
);

CREATE TABLE dbo.meter_readings (
    meter_id NVARCHAR(128) NOT NULL,
    reading_ts DATETIME2(7) NOT NULL,
    kwh DECIMAL(18, 6) NOT NULL,
    zone NVARCHAR(16) NOT NULL,
    file_id UNIQUEIDENTIFIER NULL,
    attempt_id UNIQUEIDENTIFIER NULL,
    received_at DATETIME2(7) NOT NULL
        CONSTRAINT DF_meter_readings_received_at DEFAULT (SYSUTCDATETIME()),

    CONSTRAINT UQ_meter_readings_meter_id_reading_ts UNIQUE CLUSTERED (meter_id, reading_ts),
    CONSTRAINT FK_meter_readings_files FOREIGN KEY (file_id) REFERENCES dbo.files (id),
    CONSTRAINT CK_meter_readings_zone CHECK (zone IN (N'Muntenia', N'Banat', N'Dobrogea'))
);

CREATE NONCLUSTERED INDEX IX_meter_readings_attempt_id ON dbo.meter_readings (attempt_id);
CREATE NONCLUSTERED INDEX IX_meter_readings_file_id_received_at
    ON dbo.meter_readings (file_id, received_at);
CREATE NONCLUSTERED INDEX IX_meter_readings_zone_reading_ts
    ON dbo.meter_readings (zone, reading_ts);

CREATE TABLE dbo.outage_records (
    file_id UNIQUEIDENTIFIER NOT NULL,
    zone NVARCHAR(16) NOT NULL,
    station_id NVARCHAR(128) NULL,
    occurred_at DATETIME2(7) NOT NULL,

    CONSTRAINT PK_outage_records PRIMARY KEY (file_id),
    CONSTRAINT FK_outage_records_files FOREIGN KEY (file_id) REFERENCES dbo.files (id),
    CONSTRAINT CK_outage_records_zone CHECK (zone IN (N'Muntenia', N'Banat', N'Dobrogea'))
);

CREATE NONCLUSTERED INDEX IX_outage_records_zone_occurred_at
    ON dbo.outage_records (zone, occurred_at);

CREATE TABLE dbo.audit_log (
    id UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_audit_log_id DEFAULT (NEWSEQUENTIALID()),
    ts DATETIME2(7) NOT NULL CONSTRAINT DF_audit_log_ts DEFAULT (SYSUTCDATETIME()),
    actor NVARCHAR(128) NOT NULL,
    action NVARCHAR(64) NOT NULL,
    file_id UNIQUEIDENTIFIER NULL,
    prev_hash VARBINARY(32) NULL,
    hash VARBINARY(32) NULL,

    CONSTRAINT PK_audit_log PRIMARY KEY (id),
    CONSTRAINT FK_audit_log_files FOREIGN KEY (file_id) REFERENCES dbo.files (id),
    CONSTRAINT CK_audit_log_prev_hash CHECK (prev_hash IS NULL OR DATALENGTH(prev_hash) = 32),
    CONSTRAINT CK_audit_log_hash CHECK (hash IS NULL OR DATALENGTH(hash) = 32)
);

CREATE NONCLUSTERED INDEX IX_audit_log_file_id_ts ON dbo.audit_log (file_id, ts);

CREATE TABLE dbo.operation_metrics (
    id UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_operation_metrics_id DEFAULT (NEWSEQUENTIALID()),
    category NVARCHAR(32) NOT NULL,
    storage NVARCHAR(8) NOT NULL,
    operation NVARCHAR(64) NOT NULL,
    latency_ms DECIMAL(18, 3) NOT NULL,
    ok BIT NOT NULL,
    ts DATETIME2(7) NOT NULL CONSTRAINT DF_operation_metrics_ts DEFAULT (SYSUTCDATETIME()),
    size_bytes BIGINT NULL,
    source NVARCHAR(16) NOT NULL,
    scenario NVARCHAR(128) NULL,

    CONSTRAINT PK_operation_metrics PRIMARY KEY (id),
    CONSTRAINT CK_operation_metrics_category CHECK (
        category IN (N'METER_READING', N'OUTAGE_RECORD', N'CONNECTION_DOC', N'INSPECTION_VIDEO')
    ),
    CONSTRAINT CK_operation_metrics_storage CHECK (storage IN (N'FTP', N'S3', N'DB')),
    CONSTRAINT CK_operation_metrics_latency_ms CHECK (latency_ms >= 0),
    CONSTRAINT CK_operation_metrics_size_bytes CHECK (size_bytes IS NULL OR size_bytes > 0),
    CONSTRAINT CK_operation_metrics_source CHECK (source IN (N'live', N'benchmark'))
);

CREATE NONCLUSTERED INDEX IX_operation_metrics_ts ON dbo.operation_metrics (ts);
CREATE NONCLUSTERED INDEX IX_operation_metrics_category_storage_operation_ts
    ON dbo.operation_metrics (category, storage, operation, ts);
