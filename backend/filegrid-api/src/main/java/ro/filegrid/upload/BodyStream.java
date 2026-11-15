package ro.filegrid.upload;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import ro.filegrid.integrity.Sha256;

/**
 * A chunk body read as a stream: computes SHA-256 and counts bytes without holding them in memory, records whether
 * the source (client) failed or ended too early (so the error is a 400, not "storage down") and periodically calls
 * {@code progress} (lease renewal).
 */
final class BodyStream extends FilterInputStream {

    private static final long PROGRESS_NANOS = 15_000_000_000L;

    private final long expected;
    private final Runnable progress;
    private final MessageDigest digest = Sha256.newDigest();
    private long read;
    private boolean sourceFailed;
    private long lastProgress = System.nanoTime();

    BodyStream(InputStream in, long expected, Runnable progress) {
        super(in);
        this.expected = expected;
        this.progress = progress;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n;
        try {
            n = in.read(b, off, len);
        } catch (IOException | RuntimeException e) {
            sourceFailed = true;
            throw e;
        }
        if (n < 0) {
            if (read < expected) {
                sourceFailed = true;
            }
            return n;
        }
        digest.update(b, off, n);
        read += n;
        long now = System.nanoTime();
        if (now - lastProgress > PROGRESS_NANOS) {
            lastProgress = now;
            progress.run();
        }
        return n;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    /** The body failed or ended before the expected size. */
    boolean sourceFailed() {
        return sourceFailed;
    }

    long bytesRead() {
        return read;
    }

    /** {@code true} if more bytes follow the expected ones (chunk too large). */
    boolean hasExtra() throws IOException {
        return in.read() >= 0;
    }

    byte[] sha256() {
        return digest.digest();
    }
}
