package ro.filegrid.storage;

/**
 * Byte range requested from {@link StorageAdapter#get(String, ByteRange)}. The caller converts the HTTP {@code Range}
 * header: {@code bytes=a-b} -> {@code of(a, b - a + 1)}, {@code bytes=a-} -> {@code from(a)},
 * {@code bytes=-n} -> {@code from(size - n)} (with {@code size} from {@code files.size_bytes}).
 *
 * @param offset first byte, zero-based
 * @param length number of bytes, or {@link #TO_END} for "until the end of the object"
 */
public record ByteRange(long offset, long length) {

    /** {@code length} value meaning "until the end". */
    public static final long TO_END = -1;

    private static final ByteRange FULL = new ByteRange(0, TO_END);

    public ByteRange {
        if (offset < 0) {
            throw new IllegalArgumentException("offset negativ");
        }
        if (length != TO_END && length <= 0) {
            throw new IllegalArgumentException("length este pozitiv sau TO_END");
        }
    }

    /** The whole object. */
    public static ByteRange full() {
        return FULL;
    }

    /** From {@code offset} to the end. */
    public static ByteRange from(long offset) {
        return offset == 0 ? FULL : new ByteRange(offset, TO_END);
    }

    /** {@code length} bytes from {@code offset} (or fewer if the object ends first). */
    public static ByteRange of(long offset, long length) {
        return new ByteRange(offset, length);
    }

    /** {@code true} for the whole object (offset 0, to the end). */
    public boolean isFull() {
        return offset == 0 && length == TO_END;
    }

    /** {@code true} if the range runs to the end of the object. */
    public boolean isToEnd() {
        return length == TO_END;
    }

    /**
     * How many bytes are returned for an object of {@code objectSize} bytes (0 if the offset is past the end). Useful
     * for implementations and for {@code Content-Length} / {@code Content-Range}.
     */
    public long lengthWithin(long objectSize) {
        if (offset >= objectSize) {
            return 0;
        }
        long available = objectSize - offset;
        return length == TO_END ? available : Math.min(length, available);
    }
}
