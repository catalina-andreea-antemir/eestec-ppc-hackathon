package ro.filegrid.simulator;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final AtomicLong elapsed = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryPolicy policy = new RetryPolicy(Duration.ofMinutes(10), elapsed::get,
            Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC), delay -> {
                sleeps.add(delay);
                elapsed.addAndGet(delay.toNanos());
            });

    @Test
    void only429And503TriggerBackoff() {
        for (int status = 100; status <= 599; status++) {
            assertEquals(status == 429 || status == 503, RetryPolicy.isRetryable(status));
        }
    }

    @Test
    void backoffDoublesAndCapsAtThirtySeconds() throws InterruptedException {
        long deadline = policy.deadline();
        for (int retry = 0; retry < 8; retry++) {
            assertTrue(policy.pause(retry, Optional.empty(), deadline));
        }
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L),
                sleeps.stream().map(Duration::toSeconds).toList());
    }

    @Test
    void respectsRetryAfterSecondsAndHttpDateAndIgnoresMalformedValues() throws InterruptedException {
        long deadline = policy.deadline();
        assertTrue(policy.pause(0, Optional.of("9"), deadline));
        assertTrue(policy.pause(1, Optional.of("Sat, 14 Nov 2026 00:00:15 GMT"), deadline));
        assertTrue(policy.pause(2, Optional.of("invalid"), deadline));
        assertTrue(policy.pause(3, Optional.of("0"), deadline));
        assertEquals(List.of(9L, 15L, 4L, 8L), sleeps.stream().map(Duration::toSeconds).toList());
    }

    @Test
    void retryAfterBeyondTheBudgetWaitsUntilDeadlineWithoutResending() throws InterruptedException {
        long deadline = policy.deadline();
        assertTrue(policy.pause(0, Optional.empty(), deadline));
        assertFalse(policy.pause(1, Optional.of("99999999999999999999999999"), deadline));
        assertFalse(policy.pause(2, Optional.empty(), deadline));
        assertEquals(List.of(1L, 599L), sleeps.stream().map(Duration::toSeconds).toList());
        assertEquals(Duration.ZERO, policy.remaining(deadline));
        assertEquals(Duration.ofMinutes(10), RetryPolicy.standard().budget());
    }
}
