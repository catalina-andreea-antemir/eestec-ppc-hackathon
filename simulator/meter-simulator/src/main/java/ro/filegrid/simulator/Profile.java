package ro.filegrid.simulator;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record Profile(int meters, int firstMeterNumber, Duration sendingInterval, Duration duration,
               int readingsPerFile, Instant firstReadingAt, String scenario,
               int abnormalPercent, int silentPercent, int duplicatePercent) {
    private static final Set<String> KEYS = Set.of("meters", "firstMeterNumber", "sendingInterval", "duration",
            "readingsPerFile", "firstReadingAt", "scenario", "abnormalPercent", "silentPercent", "duplicatePercent");
    static final int READING_INTERVAL_SECONDS = 15 * 60;

    Profile {
        if (meters < 1 || firstMeterNumber < 1 || (long) firstMeterNumber + meters - 1 > 9_999_999) {
            throw new IllegalArgumentException("The meter number range must fit in 1–9999999");
        }
        if (sendingInterval.compareTo(Duration.ofMillis(1)) < 0 || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Set duration above zero and sendingInterval to at least PT0.001S");
        }
        if (readingsPerFile < 15 || readingsPerFile > 700) {
            throw new IllegalArgumentException("readingsPerFile must be in 15–700");
        }
        if (!scenario.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("scenario must match [a-z0-9_-]{1,32}");
        }
        if (firstReadingAt.getNano() != 0 || firstReadingAt.getEpochSecond() % READING_INTERVAL_SECONDS != 0) {
            throw new IllegalArgumentException("firstReadingAt must start on a 15-minute UTC boundary");
        }
        long lastRound = (duration.toNanos() - 1) / sendingInterval.toNanos();
        long lastReading = (lastRound + 1) * readingsPerFile - 1;
        if (firstReadingAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || firstReadingAt.plusSeconds(lastReading * READING_INTERVAL_SECONDS)
                        .isAfter(Instant.parse("9999-12-31T23:45:00Z"))) {
            throw new IllegalArgumentException("Generated reading dates must fit in years 0001–9999");
        }
        for (int percent : new int[]{abnormalPercent, silentPercent, duplicatePercent}) {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Injection percentages must be in 0–100");
            }
        }
    }

    static Profile load(String name, Path overrides) throws IOException {
        if (!Set.of("normal", "peak").contains(name)) {
            throw new IllegalArgumentException("SIM_PROFILE must be normal or peak");
        }
        Map<String, Object> values = new HashMap<>();
        try (InputStream input = Profile.class.getResourceAsStream("/profiles/" + name + ".yml")) {
            readYaml(input, values);
        }
        if (overrides != null) {
            try (InputStream input = Files.newInputStream(overrides)) {
                readYaml(input, values);
            }
        }
        return new Profile(integer(values, "meters"), integer(values, "firstMeterNumber"),
                Duration.parse(string(values, "sendingInterval")), Duration.parse(string(values, "duration")),
                integer(values, "readingsPerFile"), Instant.parse(string(values, "firstReadingAt")),
                string(values, "scenario"), integer(values, "abnormalPercent"), integer(values, "silentPercent"),
                integer(values, "duplicatePercent"));
    }

    private static void readYaml(InputStream input, Map<String, Object> values) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object yaml = new Yaml(new SafeConstructor(options)).load(input);
        if (!(yaml instanceof Map<?, ?> entries)) {
            throw new IllegalArgumentException("Simulator configuration must be a YAML mapping");
        }
        entries.forEach((key, value) -> {
            if (!(key instanceof String name) || !KEYS.contains(name)) {
                throw new IllegalArgumentException("Unknown simulator setting: " + key);
            }
            values.put(name, value);
        });
    }

    private static int integer(Map<String, Object> values, String key) {
        if (values.get(key) instanceof Integer value) {
            return value;
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    private static String string(Map<String, Object> values, String key) {
        if (values.get(key) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException(key + " must be a string");
    }
}
