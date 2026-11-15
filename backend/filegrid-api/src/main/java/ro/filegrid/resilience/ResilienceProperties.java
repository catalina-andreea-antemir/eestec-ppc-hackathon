package ro.filegrid.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code filegrid.resilience} configuration (C11): per-storage retry with backoff and circuit breaker, plus periodic
 * health checks.
 *
 * @param retryAttempts        total attempts for repeatable operations (get, exists, delete, finish, abort)
 * @param retryInitialBackoff  pause before the second attempt; grows exponentially
 * @param retryMultiplier      backoff growth factor
 * @param retryJitter          relative random deviation of the pause (0.5 = +-50%)
 * @param failureRateThreshold failed-call percentage that opens the circuit
 * @param minimumCalls         minimum calls in the window before the threshold applies
 * @param slidingWindow        window size (in calls)
 * @param openWait             how long the circuit stays open before the HALF_OPEN probe
 * @param halfOpenCalls        probe calls allowed in HALF_OPEN
 * @param healthInterval       interval of the periodic check of each storage
 * @param healthTimeout        timeout of a single check (at most 2 s)
 * @param healthFailuresToOpen consecutive failed checks after which the circuit opens
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
