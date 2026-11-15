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
 * Scrie {@code operation_metrics} fără să blocheze cererile (planul 4.1): coadă mărginită, un fir propriu care golește
 * în loturi (la {@code flushInterval} sau {@code batchSize} rânduri) prin {@link OperationMetricsRepository#insertBatch}.
 * Coada plină aruncă rândul și incrementează {@code filegrid_opmetrics_dropped_total}; la oprire coada se golește.
 * Un lot a cărui inserare eșuează se pierde (se numără ca dropped), ca să nu blocheze recorder-ul.
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

    /** Nu blochează niciodată: coadă plină → rândul se aruncă și se numără. */
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
                    flush(batch); // lotul început nu se pierde la oprire
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
            log.warn("Inserarea a {} rânduri în operation_metrics a eșuat: {}", batch.size(), e.getMessage());
        }
    }

    /** Oprește firul și golește ce a rămas în coadă (apelat la oprirea aplicației). */
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
