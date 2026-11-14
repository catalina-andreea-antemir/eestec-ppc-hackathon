package ro.filegrid.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.LongSupplier;

record RetryPolicy(Duration budget, LongSupplier nanoTime, Clock clock, Sleeper sleeper) {
    static RetryPolicy standard() {
        return new RetryPolicy(Duration.ofMinutes(10), System::nanoTime, Clock.systemUTC(), Thread::sleep);
    }

    static boolean isRetryable(int status) {
        return status == 429 || status == 503;
    }

    long deadline() {
        return nanoTime.getAsLong() + budget.toNanos();
    }

    Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - nanoTime.getAsLong()));
    }

    boolean pause(int retryIndex, Optional<String> retryAfter, long deadline) throws InterruptedException {
        Duration exponential = Duration.ofSeconds(Math.min(30, 1L << Math.min(retryIndex, 5)));
        Duration serverDelay = retryAfter.map(this::parseRetryAfter).orElse(Duration.ZERO);
        Duration delay = exponential.compareTo(serverDelay) >= 0 ? exponential : serverDelay;
        Duration remaining = remaining(deadline);
        if (!remaining.isZero()) {
            sleeper.sleep(delay.compareTo(remaining) < 0 ? delay : remaining);
        }
        return delay.compareTo(remaining) < 0 && !remaining(deadline).isZero();
    }

    private Duration parseRetryAfter(String value) {
        String header = value.strip();
        if (header.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(header));
            } catch (NumberFormatException overflow) {
                return budget;
            }
        }
        try {
            return Duration.between(clock.instant(),
                    ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException malformed) {
            return Duration.ZERO;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }
}
