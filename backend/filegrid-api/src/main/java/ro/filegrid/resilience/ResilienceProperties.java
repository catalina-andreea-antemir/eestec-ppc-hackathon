package ro.filegrid.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurația {@code filegrid.resilience} (C11): retry cu backoff și circuit breaker per storage, plus verificarea
 * periodică a stării (health).
 *
 * @param retryAttempts        încercări totale pentru operațiile care se pot repeta (get, exists, delete, finish, abort)
 * @param retryInitialBackoff  pauza înainte de a doua încercare; crește exponențial
 * @param retryMultiplier      factorul de creștere al pauzei
 * @param retryJitter          abaterea aleatoare relativă a pauzei (0,5 = ±50%)
 * @param failureRateThreshold procentul de apeluri eșuate care deschide circuitul
 * @param minimumCalls         apeluri minime în fereastră înainte ca pragul să conteze
 * @param slidingWindow        mărimea ferestrei (în apeluri)
 * @param openWait             cât rămâne circuitul deschis înainte de proba HALF_OPEN
 * @param halfOpenCalls        apeluri de probă permise în HALF_OPEN
 * @param healthInterval       intervalul verificării periodice a fiecărui storage
 * @param healthTimeout        timeout-ul unei verificări (cel mult 2 s)
 * @param healthFailuresToOpen verificări căzute la rând după care circuitul se deschide
 */
@ConfigurationProperties("filegrid.resilience")
public record ResilienceProperties(@DefaultValue("3") int retryAttempts,
                                   @DefaultValue("100ms") Duration retryInitialBackoff,
                                   @DefaultValue("2") double retryMultiplier,
                                   @DefaultValue("0.5") double retryJitter,
                                   @DefaultValue("50") float failureRateThreshold,
                                   @DefaultValue("5") int minimumCalls,
                                   @DefaultValue("10") int slidingWindow,
                                   @DefaultValue("5s") Duration openWait,
                                   @DefaultValue("2") int halfOpenCalls,
                                   @DefaultValue("3s") Duration healthInterval,
                                   @DefaultValue("2s") Duration healthTimeout,
                                   @DefaultValue("2") int healthFailuresToOpen) {

    public static ResilienceProperties defaults() {
        return new ResilienceProperties(3, Duration.ofMillis(100), 2, 0.5, 50, 5, 10, Duration.ofSeconds(5), 2,
                Duration.ofSeconds(3), Duration.ofSeconds(2), 2);
    }
}
