package ro.filegrid.domain;

/**
 * The 3 storage backends (contract schema {@code StorageType}). Constant names are exactly the values used by the
 * API, the policy YAML and the {@code storage_type} columns ({@code CK_files_storage_type}).
 */
public enum StorageType {
    /** The {@code ftp} service (vsftpd, passive mode). */
    FTP,
    /** MinIO, through AWS SDK v2. */
    S3,
    /** SQL Server, FileGridStorage database, {@code blob_chunks} table. */
    DB
}
