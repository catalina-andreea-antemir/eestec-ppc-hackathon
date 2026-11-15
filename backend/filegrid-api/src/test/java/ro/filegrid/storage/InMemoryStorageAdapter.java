package ro.filegrid.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import ro.filegrid.domain.StorageType;

/**
 * In-memory test adapter that honours {@link StorageAdapter} and {@link ChunkedWriter} (tests only: it keeps objects
 * in {@code byte[]}, which a real adapter must never do). Can simulate a storage outage with {@link #setUp(boolean)}.
 */
public class InMemoryStorageAdapter implements StorageAdapter, ChunkedWriter {

    private final StorageType type;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    /** uploadRef -> received chunks (n -> content). */
    private final Map<String, NavigableMap<Integer, byte[]>> uploads = new ConcurrentHashMap<>();
    private volatile boolean up = true;

    public InMemoryStorageAdapter(StorageType type) {
        this.type = type;
    }

    public void setUp(boolean up) {
        this.up = up;
    }

    @Override
    public StorageType type() {
        return type;
    }

    @Override
    public long put(String key, InputStream data, long length, String contentType) {
        if (length < 1) {
            throw new IllegalArgumentException("length >= 1");
        }
        StorageKeys.requireValid(key);
        requireUp();
        byte[] bytes = readExactly(data, length);
        objects.put(key, bytes);
        return bytes.length;
    }

    @Override
    public InputStream get(String key, ByteRange range) {
        requireUp();
        byte[] bytes = objects.get(StorageKeys.requireValid(key));
        if (bytes == null) {
            throw new StorageObjectNotFoundException(type, key);
        }
        long length = range.lengthWithin(bytes.length);
        return new ByteArrayInputStream(bytes, (int) Math.min(range.offset(), bytes.length), (int) length);
    }

    @Override
    public boolean delete(String key) {
        requireUp();
        boolean removed = objects.remove(StorageKeys.requireValid(key)) != null;
        return uploads.remove(key) != null || removed;
    }

    @Override
    public boolean exists(String key) {
        requireUp();
        return objects.containsKey(StorageKeys.requireValid(key));
    }

    @Override
    public StorageHealth health() {
        return up ? StorageHealth.up(type, Duration.ZERO)
                : StorageHealth.down(type, Duration.ZERO, "simulat: storage oprit");
    }

    @Override
    public String open(String key, long totalSize, int chunkSize) {
        StorageKeys.requireValid(key);
        requireUp();
        // The reference is the key itself: a new session with the same key starts over.
        uploads.put(key, new ConcurrentSkipListMap<>());
        return key;
    }

    @Override
    public ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length) {
        requireUp();
        upload.requireChunkNumber(n);
        if (length != upload.expectedLength(n)) {
            throw new StorageException(type, "Bucata " + n + " are " + length + " octeți, se așteptau "
                    + upload.expectedLength(n));
        }
        NavigableMap<Integer, byte[]> chunks = session(upload);
        byte[] bytes = readExactly(data, length);
        chunks.put(n, bytes);
        return new ChunkReceipt(n, bytes.length, type == StorageType.S3 ? "etag-" + n : null);
    }

    @Override
    public long finish(ChunkedUpload upload, List<ChunkReceipt> chunks) {
        requireUp();
        NavigableMap<Integer, byte[]> parts = uploads.get(upload.uploadRef());
        if (parts == null) {
            byte[] done = objects.get(upload.key());
            if (done != null && done.length == upload.totalSize()) {
                return done.length; // repeated finish
            }
            throw new StorageException(type, "Upload inexistent sau anulat");
        }
        if (chunks.size() != upload.totalChunks()) {
            throw new StorageException(type, "Lipsesc bucăți");
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (ChunkReceipt receipt : chunks) {
            byte[] part = parts.get(receipt.n());
            if (part == null || part.length != receipt.size()) {
                throw new StorageException(type, "Bucata " + receipt.n() + " lipsește sau diferă");
            }
            all.writeBytes(part);
        }
        if (all.size() != upload.totalSize()) {
            throw new StorageException(type, "Mărimea finală diferă de totalSize");
        }
        objects.put(upload.key(), all.toByteArray());
        uploads.remove(upload.uploadRef());
        return all.size();
    }

    @Override
    public void abort(ChunkedUpload upload) {
        requireUp();
        uploads.remove(upload.uploadRef());
        objects.remove(upload.key());
    }

    private NavigableMap<Integer, byte[]> session(ChunkedUpload upload) {
        NavigableMap<Integer, byte[]> chunks = uploads.get(upload.uploadRef());
        if (chunks == null) {
            throw new StorageException(type, "Upload inexistent sau anulat");
        }
        return chunks;
    }

    private void requireUp() {
        if (!up) {
            throw new StorageUnavailableException(type, "simulat: storage oprit");
        }
    }

    private byte[] readExactly(InputStream data, long length) {
        try {
            byte[] bytes = data.readNBytes(Math.toIntExact(length));
            if (bytes.length != length) {
                throw new StorageException(type, "Sursa s-a terminat după " + bytes.length + " din " + length
                        + " octeți");
            }
            return bytes;
        } catch (IOException e) {
            throw new StorageException(type, "Citirea sursei a eșuat", e);
        }
    }
}
