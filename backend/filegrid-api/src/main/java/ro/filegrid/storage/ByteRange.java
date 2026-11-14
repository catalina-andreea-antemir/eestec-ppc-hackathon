package ro.filegrid.storage;

/**
 * Intervalul de octeți cerut la {@link StorageAdapter#get(String, ByteRange)}. Conversia din header-ul HTTP
 * {@code Range} o face apelantul: {@code bytes=a-b} → {@code of(a, b - a + 1)}, {@code bytes=a-} → {@code from(a)},
 * {@code bytes=-n} → {@code from(size - n)} (cu {@code size} din {@code files.size_bytes}).
 *
 * @param offset primul octet, de la 0
 * @param length numărul de octeți, sau {@link #TO_END} pentru „până la sfârșitul obiectului”
 */
public record ByteRange(long offset, long length) {

    /** Valoarea {@code length} pentru „până la sfârșit”. */
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

    /** Tot obiectul. */
    public static ByteRange full() {
        return FULL;
    }

    /** De la {@code offset} până la sfârșit. */
    public static ByteRange from(long offset) {
        return offset == 0 ? FULL : new ByteRange(offset, TO_END);
    }

    /** {@code length} octeți de la {@code offset} (sau mai puțini, dacă obiectul se termină înainte). */
    public static ByteRange of(long offset, long length) {
        return new ByteRange(offset, length);
    }

    /** {@code true} pentru tot obiectul (offset 0, până la sfârșit). */
    public boolean isFull() {
        return offset == 0 && length == TO_END;
    }

    /** {@code true} dacă intervalul merge până la sfârșitul obiectului. */
    public boolean isToEnd() {
        return length == TO_END;
    }

    /**
     * Câți octeți se întorc pentru un obiect de {@code objectSize} octeți (0 dacă offsetul e după sfârșit). Util
     * implementărilor și pentru {@code Content-Length} / {@code Content-Range}.
     */
    public long lengthWithin(long objectSize) {
        if (offset >= objectSize) {
            return 0;
        }
        long available = objectSize - offset;
        return length == TO_END ? available : Math.min(length, available);
    }
}
