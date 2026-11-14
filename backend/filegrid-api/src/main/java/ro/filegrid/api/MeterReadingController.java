package ro.filegrid.api;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.ingest.MeterReadingIngestionService;
import ro.filegrid.ingest.Zones;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.storage.db.MeterReadingBatch;

/**
 * {@code POST /api/v1/meter-readings} (C7): corpul brut se citește limitat la mărimea din politica
 * {@code METER_READING} (altfel 413), se validează, se verifică {@code Idempotency-Key = meterId_ts} al primei
 * citiri și trece la {@link MeterReadingIngestionService}. 201 la lot nou, 200 la lot deja confirmat.
 */
@RestController
public class MeterReadingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ObjectProvider<MeterReadingIngestionService> service;
    private final PolicyEngine policies;
    private final ObjectMapper json;

    public MeterReadingController(ObjectProvider<MeterReadingIngestionService> service, PolicyEngine policies,
                                  ObjectMapper json) {
        this.service = service;
        this.policies = policies;
        this.json = json;
    }

    @PostMapping("/api/v1/meter-readings")
    public ResponseEntity<MeterReadingIngestionService.Result> ingest(
            HttpServletRequest request, Authentication authentication,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) throws IOException {
        RequestScenario.read(request);
        byte[] body = readLimited(request, policies.policy(Category.METER_READING).maxSizeBytes());
        MeterReadingBatch batch = parse(body);
        checkIdempotencyKey(idempotencyKey, batch);
        MeterReadingIngestionService ingestion = service.getIfAvailable();
        if (ingestion == null) {
            throw IngestErrors.storageUnavailable("Baza de metadate nu este configurată (profilul db).");
        }
        MeterReadingIngestionService.Result result = ingestion.ingest(body, batch, authentication.getName());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    private static byte[] readLimited(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw IngestErrors.tooLarge(Category.METER_READING, maxBytes);
        }
        int limit = (int) Math.min(maxBytes, Integer.MAX_VALUE - 1);
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(limit + 1);
            if (body.length > limit) {
                throw IngestErrors.tooLarge(Category.METER_READING, maxBytes);
            }
            if (body.length == 0) {
                throw IngestErrors.validation(null, "Corpul cererii lipsește.");
            }
            return body;
        }
    }

    private MeterReadingBatch parse(byte[] body) {
        BatchJson raw;
        try {
            raw = json.readValue(body, BatchJson.class);
        } catch (JacksonException e) {
            throw IngestErrors.validation(null, "Corpul cererii nu este un lot de citiri JSON valid.");
        } catch (IOException e) {
            throw IngestErrors.validation(null, "Corpul cererii nu poate fi citit.");
        }
        if (raw == null || raw.meterId() == null || raw.meterId().isEmpty() || raw.meterId().length() > 64) {
            throw IngestErrors.validation("meterId", "Câmpul „meterId” este obligatoriu (1–64 de caractere).");
        }
        if (raw.zone() == null || !Zones.VALID.contains(raw.zone())) {
            throw IngestErrors.validation("zone", "Câmpul „zone” acceptă doar Muntenia, Banat sau Dobrogea.");
        }
        if (raw.readings() == null || raw.readings().isEmpty()) {
            throw IngestErrors.validation("readings", "Lotul trebuie să conțină cel puțin o citire.");
        }
        List<MeterReadingBatch.Reading> readings = new ArrayList<>(raw.readings().size());
        for (ReadingJson reading : raw.readings()) {
            if (reading == null || reading.ts() == null || reading.kwh() == null) {
                throw IngestErrors.validation("readings", "Fiecare citire are nevoie de „ts” și „kwh”.");
            }
            Instant ts = instant(reading.ts());
            if (ts == null) {
                throw IngestErrors.validation("readings", "„ts” trebuie să fie o dată ISO-8601 cu fus orar.");
            }
            try {
                readings.add(new MeterReadingBatch.Reading(ts, reading.kwh()));
            } catch (IllegalArgumentException e) {
                throw IngestErrors.validation("readings",
                        "Citire invalidă: „kwh” ≥ 0 cu cel mult 6 zecimale, „ts” cu precizie de cel mult 100 ns.");
            }
        }
        try {
            return new MeterReadingBatch(raw.meterId(), raw.zone(), readings);
        } catch (IllegalArgumentException e) {
            throw IngestErrors.validation("readings", "Lotul conține același „ts” de mai multe ori.");
        }
    }

    private static void checkIdempotencyKey(String key, MeterReadingBatch batch) {
        if (key == null || key.isBlank()) {
            throw IngestErrors.validation(IDEMPOTENCY_KEY, "Header-ul Idempotency-Key este obligatoriu.");
        }
        String prefix = batch.meterId() + "_";
        Instant first = batch.readings().getFirst().ts();
        if (!key.startsWith(prefix) || !first.equals(instant(key.substring(prefix.length())))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISMATCH",
                    "Idempotency-Key nu coincide cu meterId și ts-ul primei citiri din corp.");
        }
    }

    private static Instant instant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    record BatchJson(String meterId, String zone, List<ReadingJson> readings) {
    }

    record ReadingJson(String ts, BigDecimal kwh) {
    }
}
