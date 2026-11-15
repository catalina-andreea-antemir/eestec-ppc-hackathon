package ro.filegrid.resilience;

import java.io.InputStream;
import java.util.List;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.ChunkedWriter;

/** {@link ChunkedWriter} wrapped in a circuit breaker; retry only for finish / abort (idempotent, no body). */
class ResilientChunkedWriter implements ChunkedWriter {

    private final ChunkedWriter delegate;
    private final StorageGuard guard;

    ResilientChunkedWriter(ChunkedWriter delegate, StorageGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public StorageType type() {
        return delegate.type();
    }

    @Override
    public String open(String key, long totalSize, int chunkSize) {
        return guard.once(type(), () -> delegate.open(key, totalSize, chunkSize));
    }

    @Override
    public ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length) {
        return guard.once(type(), () -> delegate.append(upload, n, data, length));
    }

    @Override
    public long finish(ChunkedUpload upload, List<ChunkReceipt> chunks) {
        return guard.read(type(), () -> delegate.finish(upload, chunks));
    }

    @Override
    public void abort(ChunkedUpload upload) {
        guard.read(type(), () -> {
            delegate.abort(upload);
            return null;
        });
    }
}
