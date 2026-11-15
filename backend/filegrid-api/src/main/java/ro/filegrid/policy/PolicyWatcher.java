package ro.filegrid.policy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Checks the policy files in the background every {@code filegrid.policies.reload-interval}, so a YAML change is
 * picked up (and logged) immediately even if no request asks for a policy. {@link PolicyEngine} also reloads on
 * demand; this thread only speeds things up. A daemon thread, stopped on application shutdown.
 */
@Component
public class PolicyWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PolicyWatcher.class);

    private final PolicyEngine engine;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public PolicyWatcher(PolicyEngine engine, @Value("${filegrid.policies.reload-interval:2s}") Duration interval) {
        this.engine = engine;
        this.interval = interval.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : interval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "policy-watcher");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::check, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    private void check() {
        try {
            engine.reload();
        } catch (RuntimeException e) {
            // The thread must survive any error, otherwise background reloading would stop for good.
            log.error("Policy check failed: {}", e.getMessage(), e);
        }
    }
}
