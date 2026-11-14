package ro.filegrid.resilience;

import java.io.InputStream;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageHealth;

/** {@link StorageAdapter} înfășurat în circuit breaker și retry (vezi {@link StorageGuard}). */
class ResilientStorageAdapter implements StorageAdapter {

    private final StorageAdapter delegate;
    private final StorageGuard guard;

    ResilientStorageAdapter(StorageAdapter delegate, StorageGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public StorageType type() {
        return delegate.type();
    }

    @Override
    public long put(String key, InputStream data, long length, String contentType) {
        return guard.once(type(), () -> delegate.put(key, data, length, contentType));
    }

    @Override
    public InputStream get(String key, ByteRange range) {
        return guard.read(type(), () -> delegate.get(key, range));
    }

    @Override
    public boolean delete(String key) {
        return guard.read(type(), () -> delegate.delete(key));
    }

    @Override
    public boolean exists(String key) {
        return guard.read(type(), () -> delegate.exists(key));
    }

    /** Starea cache-uită (fără apel către storage). */
    @Override
    public StorageHealth health() {
        return guard.health(type());
    }
}
