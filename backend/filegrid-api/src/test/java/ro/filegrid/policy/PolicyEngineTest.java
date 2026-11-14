package ro.filegrid.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

class PolicyEngineTest {

    private static final int MIB = 1024 * 1024;

    @TempDir
    Path dir;

    @BeforeEach
    void copyDefaultPolicies() throws IOException {
        Path defaults = Path.of("src/main/resources/policies");
        try (Stream<Path> files = Files.list(defaults)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
    }

    @Test
    void defaultPoliciesMatchThePlan() {
        PolicyEngine engine = new PolicyEngine("classpath:/policies/", Duration.ofSeconds(2));

        assertThat(engine.policy(Category.METER_READING)).isEqualTo(new CategoryPolicy(Category.METER_READING,
                StorageType.DB, StorageType.S3, 256 * 1024, false, null, 50));
        assertThat(engine.policy(Category.OUTAGE_RECORD)).isEqualTo(new CategoryPolicy(Category.OUTAGE_RECORD,
                StorageType.S3, StorageType.DB, 20L * MIB, false, null, 20));
        assertThat(engine.policy(Category.CONNECTION_DOC)).isEqualTo(new CategoryPolicy(Category.CONNECTION_DOC,
                StorageType.DB, StorageType.S3, 20L * MIB, true, null, 10));
        assertThat(engine.policy(Category.INSPECTION_VIDEO)).isEqualTo(new CategoryPolicy(Category.INSPECTION_VIDEO,
                StorageType.FTP, StorageType.S3, 5L * 1024 * MIB, false, 16 * MIB, 4));
        // Fiecare storage este principal pentru cel puțin o categorie (4.2).
        assertThat(engine.policies().values().stream().map(CategoryPolicy::primaryStorage).distinct())
                .containsExactlyInAnyOrder(StorageType.values());
    }

    @Test
    void changingTheStorageInYamlChangesTheNextChoiceWithoutRestart() throws IOException {
        PolicyEngine engine = new PolicyEngine(dir.toString(), Duration.ZERO);
        assertThat(engine.primaryStorage(Category.INSPECTION_VIDEO)).isEqualTo(StorageType.FTP);

        replace("inspection-video.yml", "primaryStorage: FTP", "primaryStorage: DB");

        assertThat(engine.primaryStorage(Category.INSPECTION_VIDEO)).isEqualTo(StorageType.DB);
        assertThat(engine.chooseStorage(Category.INSPECTION_VIDEO, s -> true)).contains(StorageType.DB);
        assertThat(engine.primaryStorage(Category.METER_READING)).isEqualTo(StorageType.DB);
    }

    @Test
    void watcherPicksUpTheChangeInTheBackground() throws Exception {
        // Recitirea la cerere e practic oprită (1 h); doar firul din fundal poate vedea schimbarea.
        PolicyEngine engine = new PolicyEngine(dir.toString(), Duration.ofHours(1));
        PolicyWatcher watcher = new PolicyWatcher(engine, Duration.ofSeconds(1));
        watcher.start();
        try {
            replace("meter-reading.yml", "primaryStorage: DB", "primaryStorage: FTP");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (engine.primaryStorage(Category.METER_READING) != StorageType.FTP && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(engine.primaryStorage(Category.METER_READING)).isEqualTo(StorageType.FTP);
        } finally {
            watcher.stop();
        }
        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void reloadHappensAtMostOncePerInterval() throws IOException {
        MutableClock clock = new MutableClock();
        PolicyEngine engine = new PolicyEngine(PolicySource.of(dir.toString()), Duration.ofSeconds(2), clock);

        replace("outage-record.yml", "primaryStorage: S3", "primaryStorage: FTP");
        assertThat(engine.primaryStorage(Category.OUTAGE_RECORD)).as("încă în interval").isEqualTo(StorageType.S3);

        clock.advance(Duration.ofSeconds(2));
        assertThat(engine.primaryStorage(Category.OUTAGE_RECORD)).isEqualTo(StorageType.FTP);
    }

    @Test
    void invalidYamlOnReloadKeepsThePreviousPolicy() throws IOException {
        PolicyEngine engine = new PolicyEngine(dir.toString(), Duration.ZERO);

        replace("inspection-video.yml", "chunkSize: 16MiB", "chunkSize: 4MiB");
        assertThat(engine.reload()).isFalse();
        assertThat(engine.policy(Category.INSPECTION_VIDEO).chunkSizeBytes()).isEqualTo(16 * MIB);

        replace("inspection-video.yml", "chunkSize: 4MiB", "chunkSize: 32MiB");
        assertThat(engine.policy(Category.INSPECTION_VIDEO).chunkSizeBytes()).isEqualTo(32 * MIB);
    }

    @Test
    void chooseStorageFallsBackWhenThePrimaryIsDown() {
        PolicyEngine engine = new PolicyEngine(dir.toString(), Duration.ofSeconds(2));

        assertThat(engine.chooseStorage(Category.INSPECTION_VIDEO, s -> s != StorageType.FTP))
                .contains(StorageType.S3);
        assertThat(engine.chooseStorage(Category.INSPECTION_VIDEO, s -> false)).isEmpty();
    }

    @Test
    void chunkSizeMustBeBetween5And64MiB() {
        assertThatThrownBy(() -> parseVideo("chunkSize: 4MiB")).isInstanceOf(PolicyException.class)
                .hasMessageContaining("5 MiB");
        assertThatThrownBy(() -> parseVideo("chunkSize: 65MiB")).isInstanceOf(PolicyException.class)
                .hasMessageContaining("64 MiB");
        assertThat(parseVideo("chunkSize: 5MiB").chunkSizeBytes()).isEqualTo(5 * MIB);
        assertThat(parseVideo("chunkSize: 64MB").chunkSizeBytes()).isEqualTo(64 * MIB);
        assertThat(parseVideo("chunkSize: 8388608").chunkSizeBytes()).isEqualTo(8 * MIB);
    }

    @Test
    void invalidFilesAreRejectedWithTheFileName() {
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", video("").replace("fallbackStorage: S3",
                "fallbackStorage: FTP"))).isInstanceOf(PolicyException.class).hasMessageContaining("a.yml")
                .hasMessageContaining("rezervă");
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", video("primarStorage: S3")))
                .hasMessageContaining("câmp necunoscut");
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", video("").replace("FTP", "NAS")))
                .hasMessageContaining("NAS");
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", video("").replace("maxSize: 5GB\n", "")))
                .hasMessageContaining("maxSize");
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", video("").replace("4", "0")))
                .hasMessageContaining("maxConcurrentRequests");
        assertThatThrownBy(() -> PolicyParser.parseFile("a.yml", "- doar o listă"))
                .isInstanceOf(PolicyException.class);
    }

    @Test
    void everyCategoryExactlyOnce() throws IOException {
        Files.delete(dir.resolve("connection-doc.yml"));
        assertThatThrownBy(() -> new PolicyEngine(dir.toString(), Duration.ZERO))
                .isInstanceOf(PolicyException.class).hasMessageContaining("CONNECTION_DOC");

        Files.writeString(dir.resolve("connection-doc.yml"), video(""));
        assertThatThrownBy(() -> new PolicyEngine(dir.toString(), Duration.ZERO))
                .isInstanceOf(PolicyException.class).hasMessageContaining("două fișiere");
    }

    @Test
    void emptyDirectoryFailsAtStartup(@TempDir Path empty) {
        assertThatThrownBy(() -> new PolicyEngine(empty.toString(), Duration.ZERO))
                .isInstanceOf(PolicyException.class).hasMessageContaining("Nu există fișiere");
    }

    @Test
    void sizesUseBinaryUnits() {
        assertThat(DataSizes.parse("256KB")).isEqualTo(262_144);
        assertThat(DataSizes.parse("20 MB")).isEqualTo(20_971_520);
        assertThat(DataSizes.parse("16MiB")).isEqualTo(16_777_216);
        assertThat(DataSizes.parse("5GB")).isEqualTo(5_368_709_120L);
        assertThat(DataSizes.parse(1024)).isEqualTo(1024);
        assertThatThrownBy(() -> DataSizes.parse("5 TB")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policiesMapCoversAllCategories() {
        Map<Category, CategoryPolicy> all = new PolicyEngine(dir.toString(), Duration.ZERO).policies();
        assertThat(all.keySet()).isEqualTo(EnumSet.allOf(Category.class));
        assertThatThrownBy(() -> all.remove(Category.METER_READING)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static CategoryPolicy parseVideo(String chunkLine) {
        return PolicyParser.parseFile("video.yml", video("").replace("chunkSize: 16MiB", chunkLine));
    }

    private static String video(String extraLine) {
        return """
                category: INSPECTION_VIDEO
                primaryStorage: FTP
                fallbackStorage: S3
                maxSize: 5GB
                chunkSize: 16MiB
                encryption: false
                maxConcurrentRequests: 4
                """ + extraLine;
    }

    private void replace(String file, String from, String to) throws IOException {
        Path path = dir.resolve(file);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(content).contains(from);
        Files.writeString(path, content.replace(from, to), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
