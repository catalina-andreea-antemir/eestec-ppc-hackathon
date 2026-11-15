package ro.filegrid.policy;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/**
 * Per-category policy (4.2), read from {@code policies/*.yml} and <b>hot-reloaded without a restart</b>.
 *
 * <ul>
 *   <li>Location: {@code filegrid.policies.location} ({@code FILEGRID_POLICY_DIR}); defaults to
 *       {@code classpath:/policies/}. In Docker the directory is mounted, so a YAML edit reaches the container.</li>
 *   <li>Reload: at most {@code filegrid.policies.reload-interval} (default 2 s) after the last check, the first request
 *       asking for a policy re-reads the files and compares a SHA-256 of their content (not the modification time,
 *       which is unreliable on volumes mounted from Windows). {@link PolicyWatcher} also checks in the background at
 *       the same interval, so a change shows up in the log right away.</li>
 *   <li>An invalid YAML at startup stops the app; an invalid YAML on reload is logged (ERROR) and the last valid
 *       policy is kept, so an editing mistake cannot take the system down.</li>
 * </ul>
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final PolicySource source;
    private final Duration reloadInterval;
    private final Clock clock;
    private final Object reloadLock = new Object();

    private volatile Snapshot current;
    private volatile Instant lastCheck;
    private byte[] lastRejectedFingerprint;

    @Autowired
    public PolicyEngine(@Value("${filegrid.policies.location:classpath:/policies/}") String location,
                        @Value("${filegrid.policies.reload-interval:2s}") Duration reloadInterval) {
        this(PolicySource.of(location), reloadInterval, Clock.systemUTC());
    }

    public PolicyEngine(PolicySource source, Duration reloadInterval, Clock clock) {
        this.source = source;
        this.reloadInterval = reloadInterval;
        this.clock = clock;
        Map<String, byte[]> files = readFiles();
        if (files.isEmpty()) {
            throw new PolicyException("Nu există fișiere de politică (*.yml) în " + source.description());
        }
        this.current = new Snapshot(PolicyParser.parse(files), fingerprint(files));
        this.lastCheck = clock.instant();
        log.info("Policy loaded from {}: {}", source.description(), summary(current.policies()));
        warnIfStorageUnused(current.policies());
    }

    /** The category's policy (after any pending reload). */
    public CategoryPolicy policy(Category category) {
        return policies().get(category);
    }

    /** Policies of every category (after any pending reload); the map is unmodifiable. */
    public Map<Category, CategoryPolicy> policies() {
        refreshIfDue();
        return current.policies();
    }

    /** The category's primary storage: where the next file goes while that storage responds. */
    public StorageType primaryStorage(Category category) {
        return policy(category).primaryStorage();
    }

    /**
     * Picks the storage for a new write: the primary if available, otherwise the fallback if available, otherwise none
     * (the caller answers 503 with {@code Retry-After}). Availability (health, circuit breaker) is supplied by the
     * caller (C11).
     */
    public Optional<StorageType> chooseStorage(Category category, Predicate<StorageType> available) {
        CategoryPolicy policy = policy(category);
        if (available.test(policy.primaryStorage())) {
            return Optional.of(policy.primaryStorage());
        }
        if (available.test(policy.fallbackStorage())) {
            return Optional.of(policy.fallbackStorage());
        }
        return Optional.empty();
    }

    /**
     * Re-reads the files now, without waiting for the interval.
     *
     * @return {@code true} if the policy changed and the new version is valid
     */
    public boolean reload() {
        synchronized (reloadLock) {
            lastCheck = clock.instant();
            Map<String, byte[]> files;
            try {
                files = source.read();
            } catch (IOException e) {
                log.error("Cannot read the policy from {}; keeping the current policy: {}", source.description(),
                        e.getMessage());
                return false;
            }
            byte[] fingerprint = fingerprint(files);
            if (Arrays.equals(fingerprint, current.fingerprint())
                    || Arrays.equals(fingerprint, lastRejectedFingerprint)) {
                return false;
            }
            Map<Category, CategoryPolicy> policies;
            try {
                if (files.isEmpty()) {
                    throw new PolicyException("Nu există fișiere de politică (*.yml) în " + source.description());
                }
                policies = PolicyParser.parse(files);
            } catch (PolicyException e) {
                lastRejectedFingerprint = fingerprint;
                log.error("New policy from {} is invalid; keeping the current policy: {}", source.description(),
                        e.getMessage());
                return false;
            }
            Map<Category, CategoryPolicy> previous = current.policies();
            current = new Snapshot(policies, fingerprint);
            lastRejectedFingerprint = null;
            logChanges(previous, policies);
            warnIfStorageUnused(policies);
            return true;
        }
    }

    private void refreshIfDue() {
        if (Duration.between(lastCheck, clock.instant()).compareTo(reloadInterval) >= 0) {
            reload();
        }
    }

    private Map<String, byte[]> readFiles() {
        try {
            return source.read();
        } catch (IOException e) {
            throw new PolicyException("Nu pot citi politica din " + source.description(), e);
        }
    }

    private static byte[] fingerprint(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                digest.update(file.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue());
                digest.update((byte) 0);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void logChanges(Map<Category, CategoryPolicy> before, Map<Category, CategoryPolicy> after) {
        for (Category category : Category.values()) {
            CategoryPolicy old = before.get(category);
            CategoryPolicy now = after.get(category);
            if (!now.equals(old)) {
                log.info("Policy reloaded for {}: {} -> {}", category, describe(old), describe(now));
            }
        }
    }

    private static void warnIfStorageUnused(Map<Category, CategoryPolicy> policies) {
        Set<StorageType> used = EnumSet.noneOf(StorageType.class);
        policies.values().forEach(p -> used.add(p.primaryStorage()));
        Set<StorageType> unused = EnumSet.complementOf(EnumSet.copyOf(used));
        if (!unused.isEmpty()) {
            log.warn("No category uses {} as primary storage (4.2 requires every storage to be used)", unused);
        }
    }

    private static String summary(Map<Category, CategoryPolicy> policies) {
        StringBuilder text = new StringBuilder();
        policies.values().forEach(p -> text.append(text.isEmpty() ? "" : "; ").append(p.category()).append(' ')
                .append(describe(p)));
        return text.toString();
    }

    private static String describe(CategoryPolicy p) {
        if (p == null) {
            return "-";
        }
        return p.primaryStorage() + " (rezervă " + p.fallbackStorage() + ", max " + p.maxSizeBytes() + " B"
                + (p.chunkSizeBytes() == null ? "" : ", chunk " + p.chunkSizeBytes() + " B")
                + ", paralel " + p.maxConcurrentRequests() + (p.encrypted() ? ", criptat" : "") + ")";
    }

    private record Snapshot(Map<Category, CategoryPolicy> policies, byte[] fingerprint) {
        Snapshot {
            policies = Collections.unmodifiableMap(policies);
        }
    }
}
