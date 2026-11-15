package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.observability.BenchmarkService.Plan;
import ro.filegrid.storage.InMemoryStorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.db.OperationMetric;

class BenchmarkServiceTest {

    private static final Map<Category, Plan> SMALL = new EnumMap<>(Map.of(
            Category.METER_READING, new Plan(1024, 3), Category.OUTAGE_RECORD, new Plan(4096, 2),
            Category.CONNECTION_DOC, new Plan(2048, 2), Category.INSPECTION_VIDEO, new Plan(8192, 1)));

    private final List<OperationMetric> recorded = Collections.synchronizedList(new ArrayList<>());
    private final OperationRecorder recorder = new OperationRecorder(recorded::addAll, new SimpleMeterRegistry(), 1000, 50, 20);

    private ObjectProvider<OperationRecorder> provider() {
        ObjectProvider<OperationRecorder> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(recorder);
        return p;
    }

    @Test
    void runsAllTwelveCombinationsRecordsBenchmarkRowsAndLeavesNoObjects() {
        Tracking ftp = new Tracking(StorageType.FTP);
        Tracking s3 = new Tracking(StorageType.S3);
        Tracking db = new Tracking(StorageType.DB);
        StorageAdapterRegistry registry = new StorageAdapterRegistry(List.of(ftp, s3, db), List.of());

        BenchmarkService.Result result = new BenchmarkService(registry, provider(), SMALL).run();

        assertThat(result.source()).isEqualTo("benchmark");
        assertThat(result.scenario()).isEqualTo("benchmark");
        assertThat(result.results()).hasSize(12);
        assertThat(result.results()).extracting(r -> r.category() + "/" + r.storage()).doesNotHaveDuplicates();
        assertThat(result.results()).allSatisfy(r -> {
            assertThat(r.operation()).isEqualTo("upload");
            assertThat(r.errors()).isZero();
            assertThat(r.samples()).isEqualTo(SMALL.get(r.category()).samples());
            assertThat(r.sizeBytes()).isEqualTo(SMALL.get(r.category()).sizeBytes());
        });
        org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .until(() -> recorded.size() == 2 * (3 + 2 + 2 + 1) * 3);
        assertThat(recorded).allSatisfy(m -> {
            assertThat(m.source()).isEqualTo("benchmark");
            assertThat(m.scenario()).isEqualTo("benchmark");
        });
        assertThat(ftp.live).isEmpty();
        assertThat(s3.live).isEmpty();
        assertThat(db.live).isEmpty();
        recorder.shutdown();
    }

    /** Ține minte cheile aflate în storage, ca testul să poată verifica lipsa resturilor. */
    private static class Tracking extends InMemoryStorageAdapter {
        final java.util.Set<String> live = java.util.concurrent.ConcurrentHashMap.newKeySet();

        Tracking(StorageType type) {
            super(type);
        }

        @Override
        public long put(String key, InputStream data, long length, String contentType) {
            long n = super.put(key, data, length, contentType);
            live.add(key);
            return n;
        }

        @Override
        public boolean delete(String key) {
            live.remove(key);
            return super.delete(key);
        }
    }

    @Test
    void missingStorageCountsErrorsAndStillReturnsTwelveRows() {
        StorageAdapterRegistry registry = new StorageAdapterRegistry(
                List.of(new InMemoryStorageAdapter(StorageType.S3)), List.of());
        BenchmarkService.Result result = new BenchmarkService(registry, provider(), SMALL).run();
        assertThat(result.results()).hasSize(12);
        assertThat(result.results().stream().filter(r -> r.storage() == StorageType.FTP))
                .allSatisfy(r -> assertThat(r.errors()).isEqualTo(r.samples()));
        assertThat(result.results().stream().filter(r -> r.storage() == StorageType.S3))
                .allSatisfy(r -> assertThat(r.errors()).isZero());
        recorder.shutdown();
    }

    @Test
    void secondConcurrentRunGetsConflict() throws Exception {
        CountDownLatch inPut = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorageAdapter slow = new InMemoryStorageAdapter(StorageType.FTP) {
            @Override
            public long put(String key, InputStream data, long length, String contentType) {
                inPut.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.put(key, data, length, contentType);
            }
        };
        BenchmarkService service = new BenchmarkService(
                new StorageAdapterRegistry(List.of(slow), List.of()), provider(), SMALL);
        Thread first = new Thread(service::run);
        first.start();
        assertThat(inPut.await(3, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(service::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("BENCHMARK_RUNNING"));

        release.countDown();
        first.join(10_000);
        recorder.shutdown();
    }

    @Test
    void percentileUsesNearestRank() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0);
        assertThat(BenchmarkService.percentile(values, 50)).isEqualTo(50.0);
        assertThat(BenchmarkService.percentile(values, 95)).isEqualTo(100.0);
        assertThat(BenchmarkService.percentile(List.of(), 95)).isZero();
    }
}
