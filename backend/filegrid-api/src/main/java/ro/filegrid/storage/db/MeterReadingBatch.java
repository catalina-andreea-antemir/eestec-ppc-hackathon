package ro.filegrid.storage.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MeterReadingBatch(String meterId, String zone, List<Reading> readings) {

    private static final Set<String> ZONES = Set.of("Muntenia", "Banat", "Dobrogea");

    public MeterReadingBatch {
        if (meterId == null || meterId.isEmpty() || meterId.length() > 64) {
            throw new IllegalArgumentException("meterId must contain 1 to 64 characters");
        }
        if (zone == null || !ZONES.contains(zone)) {
            throw new IllegalArgumentException("Unknown meter reading zone");
        }
        readings = List.copyOf(readings);
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("The batch must contain at least one reading");
        }
        Set<Instant> timestamps = new HashSet<>();
        for (Reading reading : readings) {
            if (!timestamps.add(reading.ts())) {
                throw new IllegalArgumentException("A batch cannot contain repeated timestamps");
            }
        }
    }

    public record Reading(Instant ts, BigDecimal kwh) {

        private static final Instant MIN_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
        private static final Instant MAX_TIMESTAMP = Instant.parse("9999-12-31T23:59:59.9999999Z");
        private static final BigDecimal MAX_KWH = new BigDecimal("999999999999.999999");

        public Reading {
            Objects.requireNonNull(ts, "ts");
            Objects.requireNonNull(kwh, "kwh");
            // Rounding here would make distinct readings indistinguishable on retry.
            if (ts.getNano() % 100 != 0 || ts.isBefore(MIN_TIMESTAMP) || ts.isAfter(MAX_TIMESTAMP)) {
                throw new IllegalArgumentException("ts must fit DATETIME2(7) exactly");
            }
            if (kwh.signum() < 0 || kwh.compareTo(MAX_KWH) > 0 || kwh.stripTrailingZeros().scale() > 6) {
                throw new IllegalArgumentException("kwh must be non-negative and fit DECIMAL(18, 6) exactly");
            }
            kwh = kwh.setScale(6);
        }
    }
}
