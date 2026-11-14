package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import ro.filegrid.TestSecrets;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.download.DownloadTokenService;
import ro.filegrid.download.FileContentService;
import ro.filegrid.download.FileQueryService;
import ro.filegrid.ingest.FileIngestionService;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.MeterReadingIngestionService;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.resilience.CategoryBulkheads;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;

/**
 * Izolarea per categorie (C9) pe HTTP real, în procesul de test: „storage-ul” OUTAGE_RECORD e ținut de o poartă
 * (cereri lente), iar METER_READING și descărcările CONNECTION_DOC merg normal. Fără storage real, deci fără resturi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BulkheadIsolationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final UUID DOC_ID = UUID.fromString("0f9e8d7c-6b5a-4c3d-8e2f-1a0b9c8d7e6f");
    private static final UUID OUTAGE_ID = UUID.fromString("5b7e2c1a-90d4-4f6e-8a13-c4d2e7f0b9a6");
    private static final String BATCH = "{\"meterId\":\"RO-MUN-0004821\",\"zone\":\"Muntenia\",\"readings\":"
            + "[{\"ts\":\"2026-11-14T00:00:00Z\",\"kwh\":0.412}]}";
    private static final String KEY = "RO-MUN-0004821_2026-11-14T00:00:00Z";
    private static final int METER_REQUESTS = 300;
    private static final int METER_CLIENTS = 8;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    TokenService tokens;
    @Autowired
    DownloadTokenService links;
    @Autowired
    PolicyEngine policies;
    @Autowired
    CategoryBulkheads bulkheads;
    @Autowired
    MeterRegistry registry;
    @MockitoBean
    ro.filegrid.security.AuditLog auditLog;
    @MockitoBean
    FileIngestionService files;
    @MockitoBean
    MeterReadingIngestionService meters;
    @MockitoBean
    FileQueryService query;
    @MockitoBean
    FileContentService content;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicInteger insideOutageStorage = new AtomicInteger();

    @BeforeEach
    void stubs() throws Exception {
        // Clientul Apache implicit are doar 5 conexiuni per rută; JDK HttpClient nu limitează conexiunile paralele.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()));
        // METER_READING: ~5 ms de „storage”.
        when(meters.ingest(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(5);
            return new MeterReadingIngestionService.Result(UUID.randomUUID(), "RO-MUN-0004821", 1, "ab", false);
        });
        // OUTAGE_RECORD: storage-ul ținut slab de poartă (cel mult 30 s).
        when(files.upload(any())).thenAnswer(inv -> {
            insideOutageStorage.incrementAndGet();
            gate.await(30, TimeUnit.SECONDS);
            return outage();
        });
        when(query.lookup(DOC_ID)).thenReturn(doc());
        when(query.get(any(), eq(OUTAGE_ID), any())).thenReturn(outage());
        doAnswer(inv -> {
            FileRecord f = inv.getArgument(0);
            HttpServletResponse response = inv.getArgument(2);
            if (f.category() == Category.CONNECTION_DOC) {
                Thread.sleep(5);
            }
            response.getOutputStream().write(new byte[] {1, 2, 3});
            return null;
        }).when(content).send(any(), any(), any());
    }

    @Test
    void saturatedCategoryDoesNotDegradeOthers() throws Exception {
        int limit = policies.policy(Category.OUTAGE_RECORD).maxConcurrentRequests();
        int flood = limit * 10;
        double rejectedBefore = rejected(Category.OUTAGE_RECORD);

        runMeterLoad(100); // încălzire
        long baselineP95 = p95(runMeterLoad(METER_REQUESTS));

        // Vârful: 10x limita de cereri lente pe OUTAGE_RECORD.
        ExecutorService floodPool = Executors.newFixedThreadPool(flood);
        List<Future<ResponseEntity<Map<String, Object>>>> flooded = new ArrayList<>();
        for (int i = 0; i < flood; i++) {
            flooded.add(floodPool.submit(this::uploadOutage));
        }
        awaitTrue(() -> insideOutageStorage.get() == limit
                && rejected(Category.OUTAGE_RECORD) - rejectedBefore == flood - limit, 20_000);
        assertThat(bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse()).isEqualTo(limit);

        // În timpul vârfului: contoarele și documentele merg normal, iar 429 apare doar pe categoria saturată.
        long peakP95 = p95(runMeterLoad(METER_REQUESTS));
        List<Integer> docStatuses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            docStatuses.add(download("/api/v1/downloads/" + links.create(DOC_ID, "x").token(), null)
                    .getStatusCode().value());
        }
        assertThat(docStatuses).containsOnly(200);
        ResponseEntity<String> outageDownload = download("/api/v1/files/" + OUTAGE_ID + "/download",
                auth("dispecer", Role.DISPATCH));
        assertThat(outageDownload.getStatusCode().value()).isEqualTo(429);
        assertThat(outageDownload.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(outageDownload.getBody()).contains("\"code\":\"CATEGORY_SATURATED\"")
                .contains("\"category\":\"OUTAGE_RECORD\"");

        gate.countDown();
        int created = 0;
        int tooMany = 0;
        for (Future<ResponseEntity<Map<String, Object>>> f : flooded) {
            ResponseEntity<Map<String, Object>> r = f.get(30, TimeUnit.SECONDS);
            if (r.getStatusCode().value() == 201) {
                created++;
            } else {
                tooMany++;
                assertThat(r.getStatusCode().value()).isEqualTo(429);
                assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("1");
                assertThat(r.getBody()).containsEntry("code", "CATEGORY_SATURATED");
            }
        }
        floodPool.shutdown();
        assertThat(created).isEqualTo(limit);
        assertThat(tooMany).isEqualTo(flood - limit);
        assertThat(rejected(Category.METER_READING)).isZero();
        assertThat(rejected(Category.CONNECTION_DOC)).isZero();
        awaitTrue(() -> bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse() == 0, 5_000);

        System.out.printf("C9 p95 METER_READING: singur = %d ms, în timpul vârfului (%d cereri OUTAGE_RECORD lente, "
                + "%d x 429) = %d ms%n", baselineP95, flood, tooMany, peakP95);
        // „Fără degradare vizibilă”: cel mult 3x baza, cu un prag absolut de 50 ms pentru zgomotul de planificare.
        assertThat(peakP95).isLessThanOrEqualTo(Math.max(baselineP95 * 3, baselineP95 + 50));
    }

    @Test
    void permitIsReleasedWhenDownloadStreamBreaks() throws Exception {
        doAnswer(inv -> {
            HttpServletResponse response = inv.getArgument(2);
            response.getOutputStream().write(new byte[1024]);
            response.flushBuffer();
            throw new IOException("client căzut în mijlocul stream-ului");
        }).when(content).send(any(), any(), any());
        int limit = policies.policy(Category.CONNECTION_DOC).maxConcurrentRequests();
        for (int i = 0; i < limit * 3; i++) { // dacă permisele s-ar pierde, ar apărea 429 după `limit` cereri
            try {
                download("/api/v1/downloads/" + links.create(DOC_ID, "x").token(), null);
            } catch (RuntimeException brokenStream) {
                // clientul vede stream-ul întrerupt: tocmai cazul de testat
            }
        }
        awaitTrue(() -> bulkheads.bulkhead(Category.CONNECTION_DOC).inUse() == 0, 5_000);
        assertThat(rejected(Category.CONNECTION_DOC)).isZero();
    }

    // --- utilitare ---------------------------------------------------------------------------------------------

    private double rejected(Category category) {
        return registry.get("filegrid.bulkhead.rejected").tag("category", category.name()).counter().count();
    }

    private List<Long> runMeterLoad(int requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(METER_CLIENTS);
        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(pool.submit(() -> {
                long start = System.nanoTime();
                ResponseEntity<Map<String, Object>> r = meterRequest();
                latenciesMs.add((System.nanoTime() - start) / 1_000_000);
                assertThat(r.getStatusCode().value()).isEqualTo(201);
            }));
        }
        for (Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return latenciesMs;
    }

    private static long p95(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws Exception {
        long end = System.nanoTime() + timeoutMs * 1_000_000;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condiția nu s-a îndeplinit în " + timeoutMs + " ms");
            }
            Thread.sleep(10);
        }
    }

    private ResponseEntity<Map<String, Object>> meterRequest() {
        HttpHeaders headers = auth("contor", Role.METER_SERVICE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", KEY);
        return rest.exchange("/api/v1/meter-readings", HttpMethod.POST, new HttpEntity<>(BATCH, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> uploadOutage() {
        HttpHeaders headers = auth("teren", Role.FIELD);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("category", "OUTAGE_RECORD");
        form.add("zone", "Muntenia");
        form.add("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "avarie.csv";
            }
        });
        return rest.exchange("/api/v1/files", HttpMethod.POST, new HttpEntity<>(form, headers), MAP);
    }

    private ResponseEntity<String> download(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers == null ? new HttpHeaders() : headers),
                String.class);
    }

    private HttpHeaders auth(String user, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issueAccessToken(user, role));
        return headers;
    }

    private static FileRecord outage() {
        return new FileRecord(OUTAGE_ID, Category.OUTAGE_RECORD, "avarie.csv", "text/csv", 3, new byte[32], null,
                StorageType.S3, "k", FileRecord.CONFIRMED, false, Instant.parse("2026-11-14T09:41:12Z"), "teren",
                new FileRecord.OutageInfo("Muntenia", null, Instant.parse("2026-11-14T09:32:00Z")));
    }

    private static FileRecord doc() {
        return new FileRecord(DOC_ID, Category.CONNECTION_DOC, "contract.pdf", "application/pdf", 3, new byte[32], null,
                StorageType.FTP, "k", FileRecord.CONFIRMED, true, Instant.parse("2026-11-14T09:41:12Z"), "racordari",
                null);
    }
}
