package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ro.filegrid.TestSecrets;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.download.DownloadTokenService;
import ro.filegrid.download.FileContentService;
import ro.filegrid.download.FileQueryService;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;

/** Contractul HTTP al căutării / metadatelor / link-ului semnat (serviciile sunt simulate): parametri, paginare, 401. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileReadApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final UUID ID = UUID.fromString("5b7e2c1a-90d4-4f6e-8a13-c4d2e7f0b9a6");

    @Autowired
    TestRestTemplate rest;
    @Autowired
    TokenService tokens;
    @Autowired
    DownloadTokenService links;
    @MockitoBean
    FileQueryService query;
    @MockitoBean
    FileContentService content;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    private static FileRecord file() {
        return new FileRecord(ID, Category.OUTAGE_RECORD, "avarie.csv", "text/csv", 4_718_592L, new byte[32], null,
                StorageType.S3, "k", FileRecord.CONFIRMED, false, Instant.parse("2026-11-14T09:41:12Z"), "teren",
                new FileRecord.OutageInfo("Muntenia", "ST-1", Instant.parse("2026-11-14T09:32:00Z")));
    }

    @Test
    void searchPassesFiltersAndReturnsContractPage() {
        when(query.search(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new FileQueryService.Page(List.of(file()), 2, 5, 11, 3));

        ResponseEntity<Map<String, Object>> r = get("/api/v1/files?category=OUTAGE_RECORD&q=ava&from=2026-11-01T00:00:00Z"
                + "&to=2026-12-01T00:00:00Z&page=2&size=5");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
        assertThat(r.getBody()).containsEntry("page", 2).containsEntry("size", 5).containsEntry("totalItems", 11)
                .containsEntry("totalPages", 3);
        assertThat((List<?>) r.getBody().get("items")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) r.getBody().get("items")).get(0);
        assertThat(item).containsEntry("originalName", "avarie.csv").containsEntry("sha256Plain", null)
                .doesNotContainKeys("storageKey", "storage_key", "createdByHash");
        assertThat(item).containsKey("outage");
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(query).search(any(), eq(Category.OUTAGE_RECORD), eq("ava"), from.capture(), any(), eq(2), eq(5));
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void searchDefaultsToPage1Size20() {
        when(query.search(any(), isNull(), isNull(), isNull(), isNull(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new FileQueryService.Page(List.of(), 1, 20, 0, 0));
        assertThat(get("/api/v1/files").getStatusCode().value()).isEqualTo(200);
        verify(query).search(any(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(20));
    }

    @Test
    void invalidSearchParametersGive400InErrorFormat() {
        for (String bad : new String[]{"category=NU_EXISTA", "page=0", "page=abc", "size=0", "size=101", "from=ieri",
                "to=2026-13-45", "q=" + "x".repeat(201)}) {
            ResponseEntity<Map<String, Object>> r = get("/api/v1/files?" + bad);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(r.getBody()).as(bad).containsEntry("code", "VALIDATION_ERROR").containsKey("traceId");
        }
    }

    @Test
    void getByIdReturnsMetadataAndNonUuidIs404() {
        when(query.get(any(), eq(ID))).thenReturn(file());
        ResponseEntity<Map<String, Object>> r = get("/api/v1/files/" + ID);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("id", ID.toString()).containsEntry("storage", "S3");
        assertThat(get("/api/v1/files/nu-e-uuid").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void searchRequiresAuthorization() {
        ResponseEntity<Map<String, Object>> r = rest.exchange("/api/v1/files", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), MAP);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void downloadLinkIsCreatedForTheCallerAndWorksWithoutAuthorization() throws Exception {
        when(query.get(any(), eq(ID))).thenReturn(file());
        when(query.lookup(ID)).thenReturn(file());
        doAnswer(inv -> {
            jakarta.servlet.http.HttpServletResponse response = inv.getArgument(2);
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes 0-2/10");
            response.getOutputStream().write(new byte[]{1, 2, 3});
            return null;
        }).when(content).send(any(), any(), any());

        ResponseEntity<Map<String, Object>> created = rest.exchange("/api/v1/files/" + ID + "/download-link",
                HttpMethod.POST, new HttpEntity<>(auth()), MAP);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String url = (String) created.getBody().get("url");
        assertThat(url).startsWith("/api/v1/downloads/");
        assertThat(created.getBody().get("expiresAt")).isNotNull();

        HttpHeaders range = new HttpHeaders();
        range.set("Range", "bytes=0-2");
        range.setBearerAuth("token-expirat-ignorat"); // pe ruta publică Authorization se ignoră
        ResponseEntity<byte[]> download = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(range), byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(206);
        assertThat(download.getBody()).containsExactly(1, 2, 3);
        verify(content).send(any(), eq("bytes=0-2"), any());
    }

    @Test
    void tamperedExpiredOrJunkTokenGives401JsonError() {
        String token = links.create(ID, "x").token();
        for (String bad : new String[]{token.substring(0, token.length() - 2) + "AA", "abc", "a.b"}) {
            ResponseEntity<Map<String, Object>> r = rest.exchange("/api/v1/downloads/" + bad, HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()), MAP);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(401);
            assertThat(r.getBody()).as(bad).containsEntry("code", "UNAUTHORIZED").containsKey("traceId");
        }
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth()), MAP);
    }

    private HttpHeaders auth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issueAccessToken("dispecer", Role.DISPATCH));
        return headers;
    }
}
