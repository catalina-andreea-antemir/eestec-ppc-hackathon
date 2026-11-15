package ro.filegrid.domain;

/**
 * The 4 file categories (contract schema {@code Category}).
 * Constant names are exactly the values used by the API, the policy YAML and the {@code category} columns
 * of FileGridMeta ({@code CK_files_category}).
 */
public enum Category {
    /** Meter reading batch (1-50 KB), only accepted through {@code POST /meter-readings}. */
    METER_READING,
    /** Outage record (1-10 MB), with zone, station and timestamp. */
    OUTAGE_RECORD,
    /** Connection document (personal data), AES-GCM encrypted before reaching storage. */
    CONNECTION_DOC,
    /** Drone inspection video (100 MB - 1 GB+), chunked upload only. */
    INSPECTION_VIDEO
}
