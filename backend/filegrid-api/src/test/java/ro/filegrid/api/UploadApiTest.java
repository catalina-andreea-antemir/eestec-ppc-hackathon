package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ro.filegrid.TestSecrets;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;
import ro.filegrid.upload.ChunkAck;
import ro.filegrid.upload.UploadService;
import ro.filegrid.upload.UploadStatus;

/** Contractul HTTP al upload-ului în bucăți (coduri, forme, corp binar); serviciul e simulat. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final UUID ID = UUID.fromString("d2f7a914-6b3e-4c80-91a5-e8c0b72d4f16");

    @Autowired
    TestRestTemplate rest;
    @Autowired
    TokenService tokens;
    @MockitoBean
    UploadService uploads;

    @org.junit.jupiter.api.BeforeEach
    void category() {
        when(uploads.categoryOf(any(), any())).thenReturn(Category.INSPECTION_VIDEO); // bulkhead (C9)
    }

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    private static UploadStatus status(String state) {
        return new UploadStatus(ID, Category.INSPECTION_VIDEO, "drona.mp4", StorageType.FTP, 40_000_000L, 16_777_216,
                3, 2, 16_777_216L, state, Instant.parse("2026-11-14T10:20:55Z"), Instant.parse("2026-11-14T10:27:09Z"));
    }

    @Test
    void initReturns201WithSessionAndPassesCaller() {
        when(uploads.init(eq(Category.INSPECTION_VIDEO), eq("drona.mp4"), eq(40_000_000L), any()))
                .thenReturn(status("IN_PROGRESS"));

        ResponseEntity<Map<String, Object>> r = json("/api/v1/uploads", HttpMethod.POST,
                "{\"category\":\"INSPECTION_VIDEO\",\"fileName\":\"C:\\\\drone\\\\drona.mp4\",\"totalSize\":40000000}");

        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
        assertThat(r.getBody()).containsEntry("uploadId", ID.toString()).containsEntry("storage", "FTP")
                .containsEntry("chunkSize", 16_777_216).containsEntry("totalChunks", 3)
                .containsEntry("status", "IN_PROGRESS").containsEntry("nextChunk", 2);
        ArgumentCaptor<UploadService.Caller> caller = ArgumentCaptor.forClass(UploadService.Caller.class);
        verify(uploads).init(any(), any(), anyLong(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new UploadService.Caller("teren", false));
    }

    @Test
    void initValidationErrorsAre400() {
        assertField(json("/api/v1/uploads", HttpMethod.POST,
                "{\"category\":\"OUTAGE_RECORD\",\"fileName\":\"a.mp4\",\"totalSize\":10}"), "category");
        assertField(json("/api/v1/uploads", HttpMethod.POST,
                "{\"category\":\"INSPECTION_VIDEO\",\"fileName\":\"a.mp4\",\"totalSize\":0}"), "totalSize");
        assertField(json("/api/v1/uploads", HttpMethod.POST,
                "{\"category\":\"INSPECTION_VIDEO\",\"fileName\":\"a.mp4\"}"), "totalSize");
        assertField(json("/api/v1/uploads/nu-e-uuid", HttpMethod.GET, null), "id");
        verify(uploads, never()).init(any(), any(), anyLong(), any());
    }

    @Test
    void chunkBodyIsPassedAsStreamWithItsLength() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        when(uploads.putChunk(eq(ID), eq(2), any(), any(), anyLong())).thenAnswer(call -> {
            try (InputStream in = call.getArgument(3)) {
                received.set(in.readAllBytes());
            }
            return new ChunkAck(2, 4, "ab", 3, false);
        });

        HttpHeaders headers = auth("teren", Role.FIELD);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map<String, Object>> r = rest.exchange("/api/v1/uploads/" + ID + "/chunks/2", HttpMethod.PUT,
                new HttpEntity<>(new byte[] {1, 2, 3, 4}, headers), MAP);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("n", 2).containsEntry("nextChunk", 3).containsEntry("duplicate", false);
        assertThat(received.get()).containsExactly(1, 2, 3, 4);
        verify(uploads).putChunk(eq(ID), eq(2), any(), any(), eq(4L));
    }

    @Test
    void conflictsAreMappedWithRetryAfterAndDetails() {
        when(uploads.putChunk(any(), eq(5), any(), any(), anyLong()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "CHUNK_OUT_OF_ORDER",
                        "Se aștepta bucata 2, s-a primit 5.", null, Map.of("expectedChunk", 2, "receivedChunk", 5)));
        when(uploads.putChunk(any(), eq(2), any(), any(), anyLong()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "UPLOAD_BUSY", "ocupat", 1,
                        null));

        HttpHeaders headers = auth("teren", Role.FIELD);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map<String, Object>> order = rest.exchange("/api/v1/uploads/" + ID + "/chunks/5",
                HttpMethod.PUT, new HttpEntity<>(new byte[] {1}, headers), MAP);
        ResponseEntity<Map<String, Object>> busy = rest.exchange("/api/v1/uploads/" + ID + "/chunks/2",
                HttpMethod.PUT, new HttpEntity<>(new byte[] {1}, headers), MAP);

        assertThat(order.getStatusCode().value()).isEqualTo(409);
        assertThat(order.getBody()).containsEntry("code", "CHUNK_OUT_OF_ORDER");
        assertThat(busy.getStatusCode().value()).isEqualTo(409);
        assertThat(busy.getBody()).containsEntry("code", "UPLOAD_BUSY");
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void completeIs201FirstAndGetAbortReturnSession() {
        FileRecord file = new FileRecord(ID, Category.INSPECTION_VIDEO, "drona.mp4", "video/mp4", 40_000_000L,
                Sha256.of(new byte[] {1}), null, StorageType.FTP, ID.toString(), "CONFIRMED", false,
                Instant.parse("2026-11-14T10:31:48Z"), "teren", null);
        when(uploads.complete(eq(ID), any()))
                .thenReturn(new UploadService.Completed(file, true))
                .thenReturn(new UploadService.Completed(file, false));
        when(uploads.get(eq(ID), any())).thenReturn(status("IN_PROGRESS"));
        when(uploads.abort(eq(ID), any())).thenReturn(status("ABORTED"));

        ResponseEntity<Map<String, Object>> first = json("/api/v1/uploads/" + ID + "/complete", HttpMethod.POST, null);
        ResponseEntity<Map<String, Object>> second = json("/api/v1/uploads/" + ID + "/complete", HttpMethod.POST, null);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody()).containsEntry("id", ID.toString()).containsEntry("status", "CONFIRMED")
                .containsEntry("storage", "FTP").containsEntry("sha256", Sha256.hex(file.sha256()));
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(json("/api/v1/uploads/" + ID, HttpMethod.GET, null).getBody()).containsEntry("nextChunk", 2);
        assertThat(json("/api/v1/uploads/" + ID + "/abort", HttpMethod.POST, null).getBody())
                .containsEntry("status", "ABORTED");
    }

    @Test
    void withoutTokenIs401() {
        ResponseEntity<Map<String, Object>> r = rest.exchange("/api/v1/uploads/" + ID, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), MAP);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<Map<String, Object>> json(String path, HttpMethod method, String body) {
        HttpHeaders headers = auth("teren", Role.FIELD);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    private HttpHeaders auth(String user, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issueAccessToken(user, role));
        return headers;
    }

    private static void assertField(ResponseEntity<Map<String, Object>> r, String field) {
        assertThat(r.getStatusCode().value()).as(field).isEqualTo(400);
        assertThat(r.getBody()).containsEntry("code", "VALIDATION_ERROR").containsEntry("details",
                Map.of("field", field));
    }
}
