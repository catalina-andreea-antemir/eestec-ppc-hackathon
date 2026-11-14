package ro.filegrid.storage.db;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public record OperationMetric(String category, String storage, String operation, BigDecimal latencyMs,
                              boolean ok, Long sizeBytes, String source, String scenario) {

    private static final Set<String> CATEGORIES = Set.of(
            "METER_READING", "OUTAGE_RECORD", "CONNECTION_DOC", "INSPECTION_VIDEO");
    private static final Set<String> STORAGES = Set.of("FTP", "S3", "DB");
    private static final Set<String> SOURCES = Set.of("live", "benchmark");
    private static final BigDecimal MAX_LATENCY_MS = new BigDecimal("999999999999999.999");

    public OperationMetric {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Unknown metric category");
        }
        if (storage == null || !STORAGES.contains(storage)) {
            throw new IllegalArgumentException("Unknown metric storage");
        }
        if (operation == null || operation.isBlank() || operation.length() > 64) {
            throw new IllegalArgumentException("operation must be non-blank and fit NVARCHAR(64)");
        }
        if (latencyMs == null || latencyMs.signum() < 0) {
            throw new IllegalArgumentException("latencyMs must be non-null and non-negative");
        }
        latencyMs = latencyMs.setScale(3, RoundingMode.HALF_UP);
        if (latencyMs.compareTo(MAX_LATENCY_MS) > 0) {
            throw new IllegalArgumentException("latencyMs must fit DECIMAL(18, 3) after rounding");
        }
        if (sizeBytes != null && sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive when supplied");
        }
        if (source == null || !SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unknown metric source");
        }
        if (scenario != null && scenario.length() > 128) {
            throw new IllegalArgumentException("scenario must fit NVARCHAR(128)");
        }
    }
}
