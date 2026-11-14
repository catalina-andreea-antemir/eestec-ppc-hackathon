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
 * Politica per categorie (planul v4 4.2), citită din {@code policies/*.yml} și <b>recitită fără restart</b>.
 *
 * <ul>
 *   <li>Locația: {@code filegrid.policies.location} (variabila {@code FILEGRID_POLICY_DIR}); implicit
 *       {@code classpath:/policies/}. În Docker directorul se montează, deci o schimbare în YAML ajunge în container.</li>
 *   <li>Recitirea: la cel mult {@code filegrid.policies.reload-interval} (implicit 2 s) după ultima verificare, prima
 *       cerere care întreabă de politică recitește fișierele și compară un SHA-256 al conținutului (nu data
 *       modificării, care pe volumele montate din Windows nu e de încredere). În plus, {@link PolicyWatcher}
 *       verifică în fundal la același interval, ca schimbarea să apară în log imediat.</li>
 *   <li>Un YAML invalid la pornire oprește aplicația; un YAML invalid la recitire se loghează (ERROR) și se păstrează
 *       ultima politică validă, ca o greșeală de editare să nu oprească sistemul.</li>
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
        log.info("Politica încărcată din {}: {}", source.description(), summary(current.policies()));
        warnIfStorageUnused(current.policies());
    }

    /** Politica categoriei (după o eventuală recitire). */
    public CategoryPolicy policy(Category category) {
        return policies().get(category);
    }

    /** Politicile tuturor categoriilor (după o eventuală recitire); harta nu se modifică. */
    public Map<Category, CategoryPolicy> policies() {
        refreshIfDue();
        return current.policies();
    }

    /** Storage-ul principal al categoriei: unde ajunge următorul fișier când storage-ul răspunde. */
    public StorageType primaryStorage(Category category) {
        return policy(category).primaryStorage();
    }

    /**
     * Alege storage-ul pentru o scriere nouă: principalul dacă e disponibil, altfel cel de rezervă dacă e disponibil,
     * altfel niciunul (apelantul răspunde 503 cu {@code Retry-After}). Disponibilitatea (health, circuit breaker) o dă
     * apelantul (C11).
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
     * Recitește acum fișierele, fără să aștepte intervalul.
     *
     * @return {@code true} dacă politica s-a schimbat și noua variantă e validă
     */
    public boolean reload() {
        synchronized (reloadLock) {
            lastCheck = clock.instant();
            Map<String, byte[]> files;
            try {
                files = source.read();
            } catch (IOException e) {
                log.error("Nu pot citi politica din {}; păstrez politica actuală: {}", source.description(),
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
                log.error("Politica nouă din {} este invalidă; păstrez politica actuală: {}", source.description(),
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
                log.info("Politica recitită pentru {}: {} -> {}", category, describe(old), describe(now));
            }
        }
    }

    private static void warnIfStorageUnused(Map<Category, CategoryPolicy> policies) {
        Set<StorageType> used = EnumSet.noneOf(StorageType.class);
        policies.values().forEach(p -> used.add(p.primaryStorage()));
        Set<StorageType> unused = EnumSet.complementOf(EnumSet.copyOf(used));
        if (!unused.isEmpty()) {
            log.warn("Niciun storage principal nu este {} (planul 4.2 cere ca fiecare storage să fie folosit)", unused);
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
