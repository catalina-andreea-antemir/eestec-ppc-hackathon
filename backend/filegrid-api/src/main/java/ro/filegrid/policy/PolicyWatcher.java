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
 * Verifică fișierele de politică în fundal, la {@code filegrid.policies.reload-interval}, ca o schimbare din YAML să
 * fie preluată (și logată) imediat, chiar dacă nicio cerere nu întreabă de politică. {@link PolicyEngine} recitește
 * oricum și la cerere; acest fir doar grăbește lucrurile. Un fir daemon, oprit la închiderea aplicației.
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
            // Firul trebuie să supraviețuiască oricărei erori, altfel recitirea în fundal s-ar opri.
            log.error("Verificarea politicii a eșuat: {}", e.getMessage(), e);
        }
    }
}
