package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import ro.filegrid.storage.db.OperationMetric;

class OperationRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static OperationMetric metric(int i) {
        return new OperationMetric("OUTAGE_RECORD", "S3", "upload", BigDecimal.valueOf(i), true, 10L, "live", "peak");
    }

    private double dropped() {
        return registry.get("filegrid.opmetrics.dropped").counter().count();
    }

    @Test
    void flushesInBatchesOfBatchSizeAndTheRemainderAfterTheInterval() {
        List<List<OperationMetric>> batches = Collections.synchronizedList(new ArrayList<>());
        OperationRecorder recorder = new OperationRecorder(batches::add, registry, 100, 5, 100);
        for (int i = 0; i < 12; i++) {
            recorder.record(metric(i));
        }
        await().atMost(3, TimeUnit.SECONDS).until(() -> batches.stream().mapToInt(List::size).sum() == 12);
        assertThat(batches).allSatisfy(b -> assertThat(b.size()).isLessThanOrEqualTo(5));
        assertThat(batches.getFirst()).hasSize(5);
        assertThat(dropped()).isZero();
        recorder.shutdown();
    }

    @Test
    void fullQueueDropsAndCountsWithoutBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<OperationMetric> written = Collections.synchronizedList(new ArrayList<>());
        OperationRecorder recorder = new OperationRecorder(batch -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.addAll(batch);
        }, registry, 3, 1, 10);
        recorder.record(metric(0)); // preluat de fir, care se blochează în sink
        await().atMost(2, TimeUnit.SECONDS).until(() -> recorder.queueSize() == 0);

        long start = System.nanoTime();
        for (int i = 1; i <= 10; i++) {
            recorder.record(metric(i)); // 3 încap în coadă, 7 se aruncă
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);
        assertThat(dropped()).isEqualTo(7);

        release.countDown();
        recorder.shutdown();
        assertThat(written).hasSize(4);
    }

    @Test
    void shutdownDrainsWhatIsLeftInTheQueue() {
        List<OperationMetric> written = Collections.synchronizedList(new ArrayList<>());
        OperationRecorder recorder = new OperationRecorder(written::addAll, registry, 100, 500, 60_000);
        for (int i = 0; i < 7; i++) {
            recorder.record(metric(i));
        }
        recorder.shutdown();
        assertThat(written).hasSize(7);
        assertThat(dropped()).isZero();
    }

    @Test
    void failedInsertCountsAsDroppedAndDoesNotKillTheRecorder() {
        List<OperationMetric> written = Collections.synchronizedList(new ArrayList<>());
        boolean[] fail = {true};
        OperationRecorder recorder = new OperationRecorder(batch -> {
            if (fail[0]) {
                throw new IllegalStateException("db down");
            }
            written.addAll(batch);
        }, registry, 100, 2, 50);
        recorder.record(metric(1));
        recorder.record(metric(2));
        await().atMost(2, TimeUnit.SECONDS).until(() -> dropped() == 2);
        fail[0] = false;
        recorder.record(metric(3));
        await().atMost(2, TimeUnit.SECONDS).until(() -> written.size() == 1);
        recorder.shutdown();
    }
}
