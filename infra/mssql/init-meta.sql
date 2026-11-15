-- Flyway creates the metadata schema and its history table in dbo.
IF DB_ID(N'FileGridMeta') IS NULL
BEGIN
    EXEC (N'CREATE DATABASE [FileGridMeta];');
    ALTER DATABASE [FileGridMeta] SET READ_COMMITTED_SNAPSHOT ON;
END;
GO

USE [FileGridMeta];
GO

IF USER_ID(N'filegrid_meta_app') IS NULL
    CREATE USER [filegrid_meta_app] FOR LOGIN [filegrid_meta_app] WITH DEFAULT_SCHEMA = [dbo];

IF NOT EXISTS (
    SELECT 1
    FROM sys.database_principals
    WHERE name = N'filegrid_meta_app'
        AND type = 'S'
        AND sid = SUSER_SID(N'filegrid_meta_app')
)
    THROW 50002, N'Utilizatorul FileGridMeta existent are alt login.', 1;

GRANT CONNECT, CREATE TABLE TO [filegrid_meta_app];
GRANT ALTER, REFERENCES, SELECT, INSERT, UPDATE, DELETE ON SCHEMA::[dbo] TO [filegrid_meta_app];
GO
