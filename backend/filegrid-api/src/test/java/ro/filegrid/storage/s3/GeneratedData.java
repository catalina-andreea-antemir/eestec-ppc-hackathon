package ro.filegrid.storage.s3;

import java.io.IOException;
import java.io.InputStream;

/**
 * Deterministic pseudo-random data with random access: the byte at position {@code p} depends only on
 * {@code (seed, p)}. Allows files of hundreds of MB without holding them in memory, and expected {@code Range}
 * slices can be computed directly.
 */
final class GeneratedData {

    private GeneratedData() {
    }

    static byte at(long seed, long pos) {
        return (byte) (mix(seed + (pos >>> 3)) >>> ((pos & 7) << 3));
    }

    static byte[] bytes(long seed, long offset, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = at(seed, offset + i);
        }
        return out;
    }

    /** {@code length} bytes starting at {@code offset}. */
    static InputStream stream(long seed, long offset, long length) {
        return new InputStream() {
            private long pos = offset;
            private final long end = offset + length;

            @Override
            public int read() {
                return pos < end ? at(seed, pos++) & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len == 0) {
                    return 0;
                }
                if (pos >= end) {
                    return -1;
                }
                int n = (int) Math.min(len, end - pos);
                long word = 0;
                long wordIndex = -1;
                for (int i = 0; i < n; i++, pos++) {
                    long wi = seed + (pos >>> 3);
                    if (wi != wordIndex) {
                        wordIndex = wi;
                        word = mix(wi);
                    }
                    b[off + i] = (byte) (word >>> ((pos & 7) << 3));
                }
                return n;
            }

            @Override
            public int available() {
                return (int) Math.min(Integer.MAX_VALUE, end - pos);
            }
        };
    }

    /** A stream that throws {@link IOException} after {@code failAfter} bytes (connection dropped midway). */
    static InputStream interrupted(InputStream in, long failAfter) {
        return new InputStream() {
            private long read;

            @Override
            public int read() throws IOException {
                if (read >= failAfter) {
                    throw new IOException("conexiune întreruptă (simulat)");
                }
                int b = in.read();
                read += b >= 0 ? 1 : 0;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (read >= failAfter) {
                    throw new IOException("conexiune întreruptă (simulat)");
                }
                int r = in.read(b, off, (int) Math.min(len, failAfter - read));
                read += Math.max(r, 0);
                return r;
            }
        };
    }

    /** SplitMix64. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
