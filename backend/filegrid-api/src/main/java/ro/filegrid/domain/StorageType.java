package ro.filegrid.domain;

/**
 * Cele 3 storage-uri (contract: schema {@code StorageType}). Numele constantelor sunt exact valorile din API, din
 * YAML-ul de politică și din coloanele {@code storage_type} ({@code CK_files_storage_type}).
 */
public enum StorageType {
    /** Serviciul {@code ftp} (vsftpd, mod pasiv). */
    FTP,
    /** MinIO, prin AWS SDK v2. */
    S3,
    /** SQL Server, baza FileGridStorage, tabela {@code blob_chunks}. */
    DB
}
