package ro.filegrid.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Bulkhead cu semafor non-blocant (C9): {@link #tryAcquire()} reușește sau eșuează imediat (maxWaitDuration = 0),
 * deci o cerere respinsă nu ocupă niciun fir Tomcat. Limita se citește la fiecare acquire din {@code limit}, deci o
 * politică recitită se aplică imediat; permisele aflate în uz nu se pierd (la o limită mai mică, cererile în curs
 * se termină normal, iar cele noi intră abia când numărul în uz scade sub noua limită).
 */
public final class Bulkhead {

    private final IntSupplier limit;
    private final AtomicInteger inUse = new AtomicInteger();

    public Bulkhead(IntSupplier limit) {
        this.limit = limit;
    }

    /** Un permis; {@link #close()} e idempotent și nu aruncă. */
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

    /** Permisul, sau {@code null} dacă bulkhead-ul e saturat. */
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
