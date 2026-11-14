package ro.filegrid.storage.ftp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Date pseudo-aleatorii deterministe, cu acces aleator: octetul de la poziția {@code p} depinde doar de
 * {@code (seed, p)}. Permite fișiere de sute de MB fără să fie ținute în memorie, iar intervalele așteptate la
 * {@code Range} se calculează direct.
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

    /** {@code length} octeți de la {@code offset}. */
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

    /** Un stream care aruncă {@link IOException} după {@code failAfter} octeți (conexiune căzută la jumătate). */
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
