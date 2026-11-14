package ro.filegrid.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

final class ReadingGenerator {
    private static final String[] ZONES = {"Muntenia", "Banat", "Dobrogea"};
    private static final String[] ZONE_CODES = {"MUN", "BAN", "DOB"};
    private static final int[] HOUR_FACTORS = {
            60, 60, 60, 60, 60, 60, 80, 115, 115, 100, 100, 100,
            100, 100, 100, 100, 100, 110, 125, 125, 125, 125, 90, 90
    };
    private static final ObjectWriter JSON = new ObjectMapper().writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private final Profile profile;

    ReadingGenerator(Profile profile) {
        this.profile = profile;
    }

    boolean isSilent(int meterIndex, long round) {
        return round > 0 && selected(meterIndex, 0, profile.silentPercent());
    }

    boolean shouldDuplicate(int meterIndex, long round) {
        return selected(meterIndex, round * 53, profile.duplicatePercent());
    }

    Batch generate(int meterIndex, long round) {
        int zoneIndex = meterIndex % ZONES.length;
        int meterNumber = profile.firstMeterNumber() + meterIndex;
        String meterId = String.format(Locale.ROOT, "RO-%s-%07d", ZONE_CODES[zoneIndex], meterNumber);
        int count = profile.readingsPerFile();
        int interval = Profile.READING_INTERVAL_SECONDS;
        Instant first = profile.firstReadingAt().plusSeconds(round * count * interval);
        boolean abnormal = selected(meterIndex, round * 61 + 19, profile.abnormalPercent());
        SplittableRandom noise = new SplittableRandom(meterNumber + round * 1_000_003);
        int base = 150 + new SplittableRandom(meterNumber).nextInt(250);
        int spikeIndex = noise.nextInt(count);
        List<Reading> readings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant timestamp = first.plusSeconds((long) i * interval);
            int hour = Math.floorMod(timestamp.getEpochSecond(), 86_400) / 3_600;
            int milliKwh = base * HOUR_FACTORS[hour] * noise.nextInt(90, 111) / 10_000;
            if (abnormal && i == spikeIndex) {
                milliKwh = noise.nextInt(4_000, 7_501);
            }
            readings.add(new Reading(timestamp.toString(), BigDecimal.valueOf(milliKwh, 3)));
        }

        try {
            byte[] payload = JSON.writeValueAsBytes(new MeterReadings(meterId, ZONES[zoneIndex], readings));
            if (payload.length < 1_024 || payload.length > 50_000) {
                throw new IllegalStateException("Batch size outside the 1–50 KB range: " + payload.length);
            }
            return new Batch(meterId + "_" + readings.getFirst().ts(), count, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize meter readings", e);
        }
    }

    private static boolean selected(int meterIndex, long offset, int percent) {
        return (meterIndex * 37L + offset) % 100 < percent;
    }

    record Batch(String idempotencyKey, int count, byte[] payload) {}

    private record MeterReadings(String meterId, String zone, List<Reading> readings) {}

    private record Reading(String ts, BigDecimal kwh) {}
}
