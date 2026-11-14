IF DB_ID(N'FileGridStorage') IS NULL
    EXEC (N'CREATE DATABASE [FileGridStorage];');
GO

USE [FileGridStorage];
GO

IF OBJECT_ID(N'dbo.blob_chunks', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.blob_chunks (
        storage_key NVARCHAR(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
        chunk_offset BIGINT NOT NULL,
        chunk_data VARBINARY(MAX) NOT NULL,
        chunk_size AS CONVERT(INT, DATALENGTH(chunk_data)) PERSISTED,

        -- Offsetul în octeți pornește de la zero și permite căutări pe interval.
        CONSTRAINT PK_blob_chunks PRIMARY KEY CLUSTERED (storage_key, chunk_offset),
        CONSTRAINT CK_blob_chunks_key CHECK (LEN(storage_key) > 0),
        CONSTRAINT CK_blob_chunks_offset CHECK (chunk_offset >= 0),
        CONSTRAINT CK_blob_chunks_size CHECK (DATALENGTH(chunk_data) > 0)
    );
END;
GO

-- Opțiunile de sesiune cerute de indexul filtrat. Nu modifică setările bazei.
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
GO

IF OBJECT_ID(N'dbo.blob_versions', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.blob_versions (
        version_key NVARCHAR(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
        logical_key NVARCHAR(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
        size_bytes BIGINT NOT NULL,
        published BIT NOT NULL,

        -- blob_chunks.storage_key folosește version_key pentru versiunile gestionate de adaptor.
        -- Rândurile preexistente rămân intacte și nu sunt publicate implicit.
        CONSTRAINT PK_blob_versions PRIMARY KEY CLUSTERED (version_key),
        CONSTRAINT CK_blob_versions_key CHECK (LEN(logical_key) > 0),
        CONSTRAINT CK_blob_versions_size CHECK (size_bytes > 0)
    );
END;
GO

-- NULL în upload_chunk_size identifică versiunile create prin put().
IF COL_LENGTH(N'dbo.blob_versions', N'upload_chunk_size') IS NULL
    ALTER TABLE dbo.blob_versions ADD upload_chunk_size INT NULL;

-- Pentru o sesiune multipart înlocuită se păstrează confirmarea finish(), fără datele binare.
IF COL_LENGTH(N'dbo.blob_versions', N'retired_at') IS NULL
    ALTER TABLE dbo.blob_versions ADD retired_at DATETIME2(7) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.blob_versions')
    AND name = N'UX_blob_versions_published')
    CREATE UNIQUE INDEX UX_blob_versions_published ON dbo.blob_versions (logical_key)
        INCLUDE (size_bytes) WHERE published = 1;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.blob_versions')
    AND name = N'IX_blob_versions_logical_key')
    CREATE INDEX IX_blob_versions_logical_key ON dbo.blob_versions (logical_key);
GO

IF USER_ID(N'filegrid_storage_app') IS NULL
    CREATE USER [filegrid_storage_app] FOR LOGIN [filegrid_storage_app] WITH DEFAULT_SCHEMA = [dbo];

IF NOT EXISTS (
    SELECT 1
    FROM sys.database_principals
    WHERE name = N'filegrid_storage_app'
        AND type = 'S'
        AND sid = SUSER_SID(N'filegrid_storage_app')
)
    THROW 50003, N'Utilizatorul FileGridStorage existent are alt login.', 1;

GRANT CONNECT TO [filegrid_storage_app];
GRANT SELECT, INSERT, DELETE ON OBJECT::dbo.blob_chunks TO [filegrid_storage_app];
GRANT SELECT, INSERT, DELETE ON OBJECT::dbo.blob_versions TO [filegrid_storage_app];
GRANT UPDATE ON OBJECT::dbo.blob_versions (published) TO [filegrid_storage_app];
GRANT UPDATE ON OBJECT::dbo.blob_versions (retired_at) TO [filegrid_storage_app];
GO
