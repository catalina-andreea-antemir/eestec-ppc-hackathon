package ro.filegrid.observability;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import ro.filegrid.api.ApiException;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.db.OperationMetric;

/**
 * Storage x category benchmark: all 12 combinations through {@link StorageAdapter} directly (no encryption and no
 * {@code files} rows), with representative sizes per category. Each measurement (full upload and download) goes to
 * {@code operation_metrics} with {@code source=benchmark, scenario=benchmark}; test objects are deleted at the end,
 * even if a combination fails. Runs synchronously, one at a time per instance.
 */
@Service
@Profile("db")
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);
    static final String SOURCE = "benchmark";
    private static final double MIB = 1024.0 * 1024.0;

    /** Size and sample count per category. */
    record Plan(long sizeBytes, int samples) {
    }

    static final Map<Category, Plan> PLANS = new EnumMap<>(Map.of(
            Category.METER_READING, new Plan(10 * 1024L, 200),
            Category.OUTAGE_RECORD, new Plan(5 * 1024 * 1024L, 10),
            Category.CONNECTION_DOC, new Plan(1024 * 1024L, 20),
            Category.INSPECTION_VIDEO, new Plan(100 * 1024 * 1024L, 1)));

    public record Row(Category category, StorageType storage, String operation, int samples, long sizeBytes,
                      double latencyP50Ms, double latencyP95Ms, double throughputMBps, int errors) {
    }

    public record Result(Instant startedAt, Instant finishedAt, String source, String scenario, List<Row> results) {
    }

    private final StorageAdapterRegistry registry;
    private final ObjectProvider<OperationRecorder> recorder;
    private final AtomicBoolean running = new AtomicBoolean();

    private final Map<Category, Plan> plans;

    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkService(StorageAdapterRegistry registry, ObjectProvider<OperationRecorder> recorder) {
        this(registry, recorder, PLANS);
    }

    BenchmarkService(StorageAdapterRegistry registry, ObjectProvider<OperationRecorder> recorder,
                     Map<Category, Plan> plans) {
        this.registry = registry;
        this.recorder = recorder;
        this.plans = plans;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "BENCHMARK_RUNNING", "Un benchmark este deja în curs.");
        }
        try {
            Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            List<Row> rows = new ArrayList<>();
            for (Category category : Category.values()) {
                for (StorageType storage : StorageType.values()) {
                    rows.add(runCombination(category, storage, plans.get(category)));
                }
            }
            return new Result(started, Instant.now().truncatedTo(ChronoUnit.SECONDS), SOURCE, SOURCE, rows);
        } finally {
            running.set(false);
        }
    }

    private Row runCombination(Category category, StorageType storage, Plan plan) {
        List<Double> uploadMs = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        int errors = 0;
        double uploadSeconds = 0;
        try {
            StorageAdapter adapter = registry.adapter(storage);
            for (int i = 0; i < plan.samples(); i++) {
                String key = StorageKeys.newKey();
                keys.add(key);
                long t0 = System.nanoTime();
                boolean ok = attempt(() -> adapter.put(key, new GeneratedStream(plan.sizeBytes()), plan.sizeBytes(),
                        "application/octet-stream"));
                double ms = (System.nanoTime() - t0) / 1e6;
                record(category, storage, "upload", plan.sizeBytes(), ms, ok);
                if (ok) {
                    uploadMs.add(ms);
                    uploadSeconds += ms / 1000.0;
                    long d0 = System.nanoTime();
                    boolean read = attempt(() -> drain(adapter, key));
                    record(category, storage, "download", plan.sizeBytes(), (System.nanoTime() - d0) / 1e6, read);
                    if (!read) {
                        errors++;
                    }
                } else {
                    errors++;
                }
            }
        } catch (RuntimeException e) { // storage without an adapter: every remaining sample counts as an error
            log.warn("Benchmark {} × {}: {}", category, storage, e.getMessage());
            errors = plan.samples() - uploadMs.size();
        } finally {
            cleanup(storage, keys);
        }
        double throughput = uploadSeconds > 0 ? uploadMs.size() * plan.sizeBytes() / MIB / uploadSeconds : 0;
        return new Row(category, storage, "upload", plan.samples(), plan.sizeBytes(), round(percentile(uploadMs, 50)),
                round(percentile(uploadMs, 95)), round(throughput), errors);
    }

    private boolean attempt(Runnable call) {
        try {
            call.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void drain(StorageAdapter adapter, String key) {
        try (InputStream in = adapter.get(key, ByteRange.full())) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) >= 0) {
                // read only to measure the duration
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void record(Category category, StorageType storage, String operation, long size, double ms, boolean ok) {
        OperationRecorder rec = recorder.getIfAvailable();
        if (rec != null) {
            rec.record(new OperationMetric(category.name(), storage.name(), operation, BigDecimal.valueOf(ms), ok, size,
                    SOURCE, SOURCE));
        }
    }

    private void cleanup(StorageType storage, List<String> keys) {
        StorageAdapter adapter = registry.find(storage).orElse(null);
        if (adapter == null) {
            return;
        }
        for (String key : keys) {
            try {
                adapter.delete(key);
            } catch (RuntimeException e) {
                log.error("Benchmark: test object {} was left in {}: {}", key, storage, e.getMessage());
            }
        }
    }

    /** Nearest-rank percentile; 0 when there are no samples. */
    static double percentile(List<Double> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** {@code length} bytes generated on the fly (no allocation proportional to size), for files up to hundreds of MB. */
    static final class GeneratedStream extends InputStream {
        private static final byte[] BLOCK = new byte[64 * 1024];

        static {
            for (int i = 0; i < BLOCK.length; i++) {
                BLOCK[i] = (byte) (i * 31 + 7);
            }
        }

        private long remaining;

        GeneratedStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return BLOCK[(int) (remaining % BLOCK.length)] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            int n = (int) Math.min(Math.min(len, remaining), BLOCK.length);
            System.arraycopy(BLOCK, 0, b, off, n);
            remaining -= n;
            return n;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }
}
