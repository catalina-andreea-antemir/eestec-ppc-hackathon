package ro.filegrid.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.policy.PolicyEngine;
import ro.filegrid.api.RequestScenario;
import ro.filegrid.observability.OperationMetrics;
import ro.filegrid.observability.OperationRecorder;
import ro.filegrid.storage.db.OperationMetric;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Bulkhead-urile pe categorii (C9): fail-fast, eliberare, redimensionare la reload, izolare, contor. */
class CategoryBulkheadsTest {

    @TempDir
    Path dir;
    PolicyEngine engine;
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CategoryBulkheads bulkheads;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : new String[] {"meter-reading", "outage-record", "connection-doc", "inspection-video"}) {
            try (var in = getClass().getResourceAsStream("/policies/" + name + ".yml")) {
                Files.write(dir.resolve(name + ".yml"), in.readAllBytes());
            }
        }
        engine = new PolicyEngine(dir.toString(), Duration.ofHours(1)); // recitirea o cer explicit
        bulkheads = new CategoryBulkheads(engine, meters);
    }

    private void setOutageLimit(int limit) throws IOException {
        Path file = dir.resolve("outage-record.yml");
        Files.writeString(file, Files.readString(file).replaceAll("maxConcurrentRequests: [0-9]+",
                "maxConcurrentRequests: " + limit));
        assertThat(engine.reload()).isTrue();
    }

    @Test
    void rejectsImmediatelyAtLimitWithCategoryAndRetryAfter() {
        int limit = engine.policy(Category.CONNECTION_DOC).maxConcurrentRequests();
        List<Bulkhead.Permit> held = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            held.add(bulkheads.acquire(Category.CONNECTION_DOC));
        }
        long start = System.nanoTime();
        assertThatThrownBy(() -> bulkheads.acquire(Category.CONNECTION_DOC)).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("CATEGORY_SATURATED");
                    assertThat(e.retryAfterSeconds()).isEqualTo(1);
                    assertThat(e.details()).containsEntry("category", "CONNECTION_DOC");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        assertThat(meters.get("filegrid.bulkhead.rejected").tag("category", "CONNECTION_DOC").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("filegrid.bulkhead.rejected").tag("category", "METER_READING").counter().count())
                .isZero();
        held.forEach(Bulkhead.Permit::close);
        bulkheads.acquire(Category.CONNECTION_DOC).close();
    }

    @Test
    void rejectionWritesOneRejectedRowWithPrimaryStorageAndScenario() {
        List<OperationMetric> rows = new CopyOnWriteArrayList<>();
        var recorder = new OperationRecorder(rows::addAll, meters, 100, 10, 20);
        var provider = new StaticListableBeanFactory(Map.of("recorder", recorder))
                .getBeanProvider(OperationRecorder.class);
        var recording = new CategoryBulkheads(engine, meters, new OperationMetrics(meters, provider));
        var request = new MockHttpServletRequest();
        request.addHeader(RequestScenario.HEADER, "storm");
        RequestScenario.read(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            int limit = engine.policy(Category.OUTAGE_RECORD).maxConcurrentRequests();
            List<Bulkhead.Permit> held = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                held.add(recording.acquire(Category.OUTAGE_RECORD));
            }
            assertThatThrownBy(() -> recording.acquire(Category.OUTAGE_RECORD)).isInstanceOf(ApiException.class);
            held.forEach(Bulkhead.Permit::close);
            recording.acquire(Category.OUTAGE_RECORD).close(); // o cerere servită nu scrie rânduri aici
        } finally {
            RequestContextHolder.resetRequestAttributes();
            recorder.shutdown();
        }
        assertThat(rows).containsExactly(new OperationMetric("OUTAGE_RECORD",
                engine.policy(Category.OUTAGE_RECORD).primaryStorage().name(), OperationMetrics.BULKHEAD_REJECTED,
                BigDecimal.ZERO, false, null, "live", "storm"));
    }

    @Test
    void releasesOnExceptionAndCloseIsIdempotent() {
        int limit = engine.policy(Category.INSPECTION_VIDEO).maxConcurrentRequests();
        for (int i = 0; i < limit * 3; i++) {
            assertThatThrownBy(() -> {
                try (var permit = bulkheads.acquire(Category.INSPECTION_VIDEO)) {
                    throw new IllegalStateException("storage a căzut");
                }
            }).isInstanceOf(IllegalStateException.class);
        }
        assertThat(bulkheads.bulkhead(Category.INSPECTION_VIDEO).inUse()).isZero();

        Bulkhead.Permit permit = bulkheads.acquire(Category.INSPECTION_VIDEO);
        permit.close();
        permit.close(); // stream închis de două ori (terminat + client căzut) nu eliberează de două ori
        assertThat(bulkheads.bulkhead(Category.INSPECTION_VIDEO).inUse()).isZero();
    }

    @Test
    void resizesOnPolicyReloadWithoutLosingPermitsInUse() throws IOException {
        setOutageLimit(3);
        List<Bulkhead.Permit> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(bulkheads.acquire(Category.OUTAGE_RECORD));
        }
        assertThatThrownBy(() -> bulkheads.acquire(Category.OUTAGE_RECORD)).isInstanceOf(ApiException.class);

        setOutageLimit(5); // creștere: două locuri noi, imediat
        held.add(bulkheads.acquire(Category.OUTAGE_RECORD));
        held.add(bulkheads.acquire(Category.OUTAGE_RECORD));
        assertThatThrownBy(() -> bulkheads.acquire(Category.OUTAGE_RECORD)).isInstanceOf(ApiException.class);

        setOutageLimit(2); // scădere sub numărul în uz: cele 5 în curs rămân, nu intră altele până scade sub 2
        assertThat(bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse()).isEqualTo(5);
        held.subList(0, 3).forEach(Bulkhead.Permit::close); // rămân 2 în uz = limita
        assertThatThrownBy(() -> bulkheads.acquire(Category.OUTAGE_RECORD)).isInstanceOf(ApiException.class);
        held.get(3).close(); // 1 în uz < 2
        Bulkhead.Permit again = bulkheads.acquire(Category.OUTAGE_RECORD);
        assertThat(bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse()).isEqualTo(2);
        again.close();
        held.get(4).close();
        assertThat(bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse()).isZero();
    }

    @Test
    void categoriesAreIsolated() {
        int limit = engine.policy(Category.CONNECTION_DOC).maxConcurrentRequests();
        List<Bulkhead.Permit> held = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            held.add(bulkheads.acquire(Category.CONNECTION_DOC));
        }
        assertThatThrownBy(() -> bulkheads.acquire(Category.CONNECTION_DOC)).isInstanceOf(ApiException.class);
        for (Category other : new Category[] {Category.METER_READING, Category.OUTAGE_RECORD,
                Category.INSPECTION_VIDEO}) {
            bulkheads.acquire(other).close(); // celelalte categorii nu sunt afectate
        }
        held.forEach(Bulkhead.Permit::close);
    }

    @Test
    void concurrentAcquiresNeverExceedLimit() throws Exception {
        int limit = engine.policy(Category.OUTAGE_RECORD).maxConcurrentRequests();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var inside = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < 5000; i++) {
                    try (var permit = bulkheads.acquire(Category.OUTAGE_RECORD)) {
                        peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        inside.decrementAndGet();
                    } catch (ApiException expected) {
                        // saturat
                    }
                }
            }));
        }
        for (var task : tasks) {
            task.get();
        }
        pool.shutdown();
        assertThat(peak.get()).isLessThanOrEqualTo(limit);
        assertThat(bulkheads.bulkhead(Category.OUTAGE_RECORD).inUse()).isZero();
    }
}
