package ro.filegrid.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

import ro.filegrid.storage.db.OperationMetric;
import ro.filegrid.storage.db.OperationMetricsRepository;

/**
 * Writes {@code operation_metrics} without blocking requests: a bounded queue and a dedicated thread that flushes in
 * batches (every {@code flushInterval} or {@code batchSize} rows) via {@link OperationMetricsRepository#insertBatch}.
 * A full queue drops the row and increments {@code filegrid_opmetrics_dropped_total}; the queue is drained on shutdown.
 * A batch whose insert fails is lost (counted as dropped) so it cannot stall the recorder.
 */
@Component
@Profile("db")
public class OperationRecorder {

    private static final Logger log = LoggerFactory.getLogger(OperationRecorder.class);

    private final BlockingQueue<OperationMetric> queue;
    private final Consumer<List<OperationMetric>> sink;
    private final int batchSize;
    private final long flushMillis;
    private final Counter dropped;
    private final Thread worker;
    private volatile boolean running = true;

    @Autowired
    public OperationRecorder(OperationMetricsRepository repository, MeterRegistry registry,
                             @Value("${filegrid.opmetrics.queue-capacity:10000}") int capacity,
                             @Value("${filegrid.opmetrics.batch-size:500}") int batchSize,
                             @Value("${filegrid.opmetrics.flush-interval-ms:500}") long flushMillis) {
        this(repository::insertBatch, registry, capacity, batchSize, flushMillis);
    }

    public OperationRecorder(Consumer<List<OperationMetric>> sink, MeterRegistry registry, int capacity,
                             int batchSize, long flushMillis) {
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.flushMillis = flushMillis;
        this.dropped = Counter.builder("filegrid.opmetrics.dropped")
                .description("Rânduri operation_metrics pierdute (coadă plină sau inserare eșuată)")
                .register(registry);
        this.worker = Thread.ofPlatform().name("opmetrics-recorder").daemon(true).start(this::loop);
    }

    /** Never blocks: full queue -> the row is dropped and counted. */
    public void record(OperationMetric metric) {
        if (!running || !queue.offer(metric)) {
            dropped.increment();
        }
    }

    int queueSize() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            List<OperationMetric> batch = new ArrayList<>(batchSize);
            try {
                OperationMetric first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushMillis);
                while (batch.size() < batchSize) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    OperationMetric next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                flush(batch);
            } catch (InterruptedException e) {
                if (!batch.isEmpty()) {
                    flush(batch); // a batch already started is not lost on shutdown
                }
                return;
            }
        }
    }

    private void flush(List<OperationMetric> batch) {
        try {
            sink.accept(List.copyOf(batch));
        } catch (RuntimeException e) {
            dropped.increment(batch.size());
            log.warn("Inserting {} rows into operation_metrics failed: {}", batch.size(), e.getMessage());
        }
    }

    /** Stops the thread and drains whatever is left in the queue (called on application shutdown). */
    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<OperationMetric> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (int from = 0; from < rest.size(); from += batchSize) {
            flush(rest.subList(from, Math.min(rest.size(), from + batchSize)));
        }
    }
}
