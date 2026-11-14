package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import ro.filegrid.TestSecrets;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileIngestionService;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.MeterReadingIngestionService;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;
import ro.filegrid.storage.db.MeterReadingBatch;

/** Contractul HTTP al ingestiei (validări, coduri, forma răspunsurilor); serviciile sunt simulate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionApiTest {

    private static final String BATCH = """
            {"meterId":"RO-MUN-0004821","zone":"Muntenia","readings":[
              {"ts":"2026-11-14T00:00:00Z","kwh":0.412},{"ts":"2026-11-14T00:15:00Z","kwh":0.397}]}""";
    private static final String KEY = "RO-MUN-0004821_2026-11-14T00:00:00Z";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    TokenService tokens;
    @MockitoBean
    FileIngestionService files;
    @MockitoBean
    MeterReadingIngestionService meters;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    @Test
    void outageUploadReturnsFileMetadata() {
        FileRecord record = new FileRecord(UUID.randomUUID(), Category.OUTAGE_RECORD, "avarie.csv", "text/csv", 3,
                Sha256.of(new byte[] {1, 2, 3}), null, StorageType.S3, "k", "CONFIRMED", false,
                Instant.parse("2026-11-14T09:41:12Z"), "teren",
                new FileRecord.OutageInfo("Muntenia", null, Instant.parse("2026-11-14T09:32:00Z")));
        when(files.upload(any())).thenReturn(record);

        ResponseEntity<Map<String, Object>> r = upload(form("OUTAGE_RECORD", "Muntenia", "2026-11-14T11:32:00+02:00"),
                "X-Scenario", "peak");

        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
        assertThat(r.getBody()).containsEntry("storage", "S3").containsEntry("status", "CONFIRMED")
                .containsEntry("sha256", Sha256.hex(record.sha256())).containsEntry("createdBy", "teren")
                .containsEntry("sha256Plain", null).containsEntry("encrypted", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> outage = (Map<String, Object>) r.getBody().get("outage");
        assertThat(outage).containsEntry("zone", "Muntenia")
                .containsEntry("stationId", null).containsEntry("occurredAt", "2026-11-14T09:32:00Z");
        ArgumentCaptor<FileIngestionService.Upload> sent = ArgumentCaptor.forClass(FileIngestionService.Upload.class);
        verify(files).upload(sent.capture());
        assertThat(sent.getValue().outage().occurredAt()).isEqualTo(Instant.parse("2026-11-14T09:32:00Z"));
        assertThat(sent.getValue().originalName()).isEqualTo("avarie.csv");
        assertThat(sent.getValue().createdBy()).isEqualTo("teren");
    }

    @Test
    void outageValidationErrorsAre400WithField() {
        assertField(upload(form(null, "Muntenia", null)), "category");
        assertField(upload(form("METER_READING", "Muntenia", null)), "category");
        assertField(upload(form("OUTAGE_RECORD", null, null)), "zone");
        assertField(upload(form("OUTAGE_RECORD", "Ardeal", null)), "zone");
        assertField(upload(form("OUTAGE_RECORD", "Banat", "ieri")), "occurredAt");
        assertField(upload(form("OUTAGE_RECORD", "Banat", null), "X-Scenario", "Vârf!"), "X-Scenario");
        verify(files, never()).upload(any());
    }

    @Test
    void uploadWithoutTokenIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map<String, Object>> r = rest.exchange("/api/v1/files", HttpMethod.POST,
                new HttpEntity<>(form("OUTAGE_RECORD", "Banat", null), headers), MAP);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    void newBatchIs201AndDuplicateIs200() {
        UUID fileId = UUID.randomUUID();
        when(meters.ingest(any(), any(), eq("contor")))
                .thenReturn(new MeterReadingIngestionService.Result(fileId, "RO-MUN-0004821", 2, "ab", false))
                .thenReturn(new MeterReadingIngestionService.Result(fileId, "RO-MUN-0004821", 2, "ab", true));

        ResponseEntity<Map<String, Object>> first = readings(BATCH, KEY);
        ResponseEntity<Map<String, Object>> second = readings(BATCH, KEY);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody()).containsEntry("fileId", fileId.toString()).containsEntry("duplicate", false)
                .containsEntry("readingsCount", 2);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).containsEntry("duplicate", true);
        ArgumentCaptor<MeterReadingBatch> batch = ArgumentCaptor.forClass(MeterReadingBatch.class);
        verify(meters, org.mockito.Mockito.times(2)).ingest(any(), batch.capture(), eq("contor"));
        assertThat(batch.getValue().readings()).hasSize(2);
    }

    @Test
    void idempotencyKeyMustMatchFirstReading() {
        when(meters.ingest(any(), any(), any())).thenReturn(
                new MeterReadingIngestionService.Result(UUID.randomUUID(), "RO-MUN-0004821", 2, "ab", false));
        assertThat(readings(BATCH, "RO-MUN-0004821_2026-11-14T00:15:00Z").getBody())
                .containsEntry("code", "IDEMPOTENCY_KEY_MISMATCH").containsEntry("status", 400);
        assertThat(readings(BATCH, "RO-MUN-0004821_2026-11-14T02:00:00+02:00").getStatusCode().value())
                .as("același moment, alt fus orar").isEqualTo(201);
        assertField(readings(BATCH, null), "Idempotency-Key");
        verify(meters).ingest(any(), any(), any());
    }

    @Test
    void invalidBatchesAre400AndOversizedIs413() {
        assertField(readings("{\"meterId\":\"m\",\"zone\":\"Ardeal\",\"readings\":[]}", "m_x"), "zone");
        assertField(readings("{\"meterId\":\"m\",\"zone\":\"Banat\",\"readings\":[]}", "m_x"), "readings");
        assertField(readings("{\"meterId\":\"m\",\"zone\":\"Banat\",\"readings\":[{\"ts\":\"2026-11-14T00:00:00Z\","
                + "\"kwh\":-1}]}", "m_2026-11-14T00:00:00Z"), "readings");
        assertThat(readings("nu e json", KEY).getBody()).containsEntry("code", "VALIDATION_ERROR");

        ResponseEntity<Map<String, Object>> big = readings(BATCH + " ".repeat(300 * 1024), KEY);
        assertThat(big.getStatusCode().value()).isEqualTo(413);
        assertThat(big.getBody()).containsEntry("code", "PAYLOAD_TOO_LARGE");
        verify(meters, never()).ingest(any(), any(), any());
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private ResponseEntity<Map<String, Object>> upload(MultiValueMap<String, Object> form, String... header) {
        HttpHeaders headers = auth("teren", Role.FIELD);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (header.length == 2) {
            headers.add(header[0], header[1]);
        }
        return rest.exchange("/api/v1/files", HttpMethod.POST, new HttpEntity<>(form, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> readings(String body, String key) {
        HttpHeaders headers = auth("contor", Role.METER_SERVICE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return rest.exchange("/api/v1/meter-readings", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private HttpHeaders auth(String user, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issueAccessToken(user, role));
        return headers;
    }

    private static MultiValueMap<String, Object> form(String category, String zone, String occurredAt) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        if (category != null) {
            form.add("category", category);
        }
        if (zone != null) {
            form.add("zone", zone);
        }
        if (occurredAt != null) {
            form.add("occurredAt", occurredAt);
        }
        form.add("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "C:\\teren\\avarie.csv";
            }
        });
        return form;
    }

    private static void assertField(ResponseEntity<Map<String, Object>> r, String field) {
        assertThat(r.getStatusCode().value()).as(field).isEqualTo(400);
        assertThat(r.getBody()).containsEntry("code", "VALIDATION_ERROR").containsEntry("details",
                Map.of("field", field));
        assertThat(r.getBody().get("traceId")).isNotNull();
    }
}
