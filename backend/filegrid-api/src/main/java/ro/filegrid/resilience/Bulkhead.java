package ro.filegrid.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Non-blocking semaphore bulkhead (C9): {@link #tryAcquire()} succeeds or fails immediately (maxWaitDuration = 0),
 * so a rejected request never holds a Tomcat thread. The limit is read from {@code limit} on every acquire, so a
 * reloaded policy applies immediately; permits in use are not lost (with a lower limit, in-flight requests finish
 * normally and new ones get in only once usage drops below the new limit).
 */
public final class Bulkhead {

    private final IntSupplier limit;
    private final AtomicInteger inUse = new AtomicInteger();

    public Bulkhead(IntSupplier limit) {
        this.limit = limit;
    }

    /** A permit; {@link #close()} is idempotent and never throws. */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                inUse.decrementAndGet();
            }
        }
    }

    /** The permit, or {@code null} if the bulkhead is saturated. */
    public Permit tryAcquire() {
        int max = limit.getAsInt();
        while (true) {
            int current = inUse.get();
            if (current >= max) {
                return null;
            }
            if (inUse.compareAndSet(current, current + 1)) {
                return new Permit();
            }
        }
    }

    public int inUse() {
        return inUse.get();
    }

    public int limit() {
        return limit.getAsInt();
    }
}
