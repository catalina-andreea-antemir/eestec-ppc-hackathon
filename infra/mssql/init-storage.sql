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
GO
