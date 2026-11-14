package ro.filegrid.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadingGeneratorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void profilesSetDifferentLoadAndEnablePeakInjections() throws IOException {
        Profile normal = Profile.load("normal", null);
        Profile peak = Profile.load("peak", null);
        assertEquals(96, normal.readingsPerFile());
        assertEquals(96, peak.readingsPerFile());
        assertEquals("normal", normal.scenario());
        assertEquals("peak", peak.scenario());
        assertTrue(peak.meters() > normal.meters());
        assertTrue(peak.sendingInterval().compareTo(normal.sendingInterval()) < 0);
        assertTrue(normal.firstMeterNumber() + normal.meters() <= peak.firstMeterNumber());
        assertEquals(0, normal.abnormalPercent() + normal.duplicatePercent() + normal.silentPercent());
        assertTrue(peak.abnormalPercent() > 0 && peak.duplicatePercent() > 0 && peak.silentPercent() > 0);
    }

    @Test
    void generationIsDeterministicAndBatchesAreContiguousAndUniquePerMeter() throws IOException {
        Profile profile = Profile.load("normal", null);
        ReadingGenerator generator = new ReadingGenerator(profile);
        Set<String> ids = new HashSet<>();
        Set<String> zones = new HashSet<>();
        Set<String> readingKeys = new HashSet<>();
        Duration spacing = Duration.ofMinutes(15);
        for (int meter = 0; meter < profile.meters(); meter++) {
            Instant expectedTimestamp = profile.firstReadingAt();
            for (int round = 0; round < 3; round++) {
                var batch = generator.generate(meter, round);
                assertArrayEquals(batch.payload(), new ReadingGenerator(profile).generate(meter, round).payload());
                JsonNode payload = JSON.readTree(batch.payload());
                assertEquals(3, payload.size());
                String id = payload.path("meterId").asText();
                assertTrue(id.matches("RO-(MUN|BAN|DOB)-[0-9]{7}"));
                if (round == 0) {
                    assertTrue(ids.add(id));
                }
                zones.add(payload.path("zone").asText());
                JsonNode readings = payload.path("readings");
                assertEquals(96, readings.size());
                assertEquals(id + "_" + readings.get(0).path("ts").asText(), batch.idempotencyKey());
                for (JsonNode reading : readings) {
                    assertEquals(2, reading.size());
                    String timestamp = reading.path("ts").asText();
                    assertEquals(expectedTimestamp.toString(), timestamp);
                    assertTrue(readingKeys.add(id + "_" + timestamp));
                    assertTrue(reading.path("kwh").isNumber());
                    assertTrue(reading.path("kwh").asDouble() > 0 && reading.path("kwh").asDouble() < 0.6);
                    expectedTimestamp = expectedTimestamp.plus(spacing);
                }
            }
        }
        assertEquals(Set.of("Muntenia", "Banat", "Dobrogea"), zones);
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 96, 700})
    void configuredBatchSizesFitTheContractEvenWithSpikes(int count) throws IOException {
        Profile profile = overrides("readingsPerFile: " + count + "\nabnormalPercent: 100\n");
        ReadingGenerator generator = new ReadingGenerator(profile);
        for (int meter = 0; meter < 3; meter++) {
            var batch = generator.generate(meter, 0);
            assertTrue(batch.payload().length >= 1_024 && batch.payload().length <= 50_000,
                    () -> "Size was " + batch.payload().length + " bytes");
            assertEquals(count, batch.count());
            int spikes = 0;
            for (JsonNode reading : JSON.readTree(batch.payload()).path("readings")) {
                if (reading.path("kwh").asDouble() >= 4) {
                    spikes++;
                }
            }
            assertEquals(1, spikes);
        }
    }

    @Test
    void silenceStartsAfterTheFirstRoundAndDuplicateSelectionHonorsPercentage() throws IOException {
        Profile profile = overrides("silentPercent: 23\nduplicatePercent: 31\nscenario: storm\n");
        ReadingGenerator generator = new ReadingGenerator(profile);
        int silent = 0;
        int duplicates = 0;
        for (int meter = 0; meter < 100; meter++) {
            assertFalse(generator.isSilent(meter, 0));
            if (generator.isSilent(meter, 1)) {
                silent++;
                assertTrue(generator.isSilent(meter, 2));
            }
            if (generator.shouldDuplicate(meter, 1)) {
                duplicates++;
            }
        }
        assertEquals(23, silent);
        assertEquals(31, duplicates);
        assertEquals("storm", profile.scenario());
    }

    @Test
    void rejectsInvalidSizeTimeAndUnknownSettings() {
        assertThrows(IllegalArgumentException.class, () -> overrides("readingsPerFile: 14\n"));
        assertThrows(IllegalArgumentException.class, () -> overrides("readingsPerFile: 701\n"));
        assertThrows(IllegalArgumentException.class, () -> overrides("firstReadingAt: '2026-11-01T00:01:00Z'\n"));
        assertThrows(IllegalArgumentException.class, () -> overrides("readingPerFile: 20\n"));
    }

    private Profile overrides(String content) throws IOException {
        Path file = Files.writeString(directory.resolve("overrides.yml"), content);
        return Profile.load("normal", file);
    }
}
