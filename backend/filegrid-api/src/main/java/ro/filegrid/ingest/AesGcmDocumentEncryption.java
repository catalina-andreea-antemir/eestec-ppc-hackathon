package ro.filegrid.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ro.filegrid.policy.CategoryPolicy;
import ro.filegrid.policy.PolicyEngine;

/**
 * Streaming AES-256-GCM over 64 KiB segments. The key comes from {@code DOC_ENCRYPTION_KEY} (base64, 32 bytes) and
 * is never logged. The app refuses to start with a missing or invalid key only if some policy has
 * {@code encryption: true}.
 *
 * <p>Format: {@code [version 1B][salt 16B][nonce prefix 4B]} followed by segments {@code [ciphertext + tag 16B]}. Each
 * file key is derived from the master key and the salt (HMAC-SHA256), so a 4-byte prefix collision between files
 * never reuses a (key, nonce) pair. Nonce = prefix + 8-byte segment counter; AAD = version, segment index and the
 * "last segment" flag, so reordering or truncation makes the tag check fail. Every segment is full (64 KiB) except
 * the last; an empty file has a single segment (tag only).
 */
@Component
public class AesGcmDocumentEncryption implements DocumentEncryption {

    public static final int SEGMENT = 64 * 1024;
    static final int VERSION = 1;
    static final int SALT = 16;
    static final int PREFIX = 4;
    static final int HEADER = 1 + SALT + PREFIX;
    static final int TAG = 16;
    /** Name: version 1B + nonce 12B + text + tag, base64; at most 350 UTF-8 bytes fit in NVARCHAR(512). */
    static final int NAME_MAX_BYTES = 350;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] NAME_CONTEXT = "filegrid-name-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FILE_CONTEXT = "filegrid-file-v1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] master;

    @Autowired
    public AesGcmDocumentEncryption(@Value("${filegrid.encryption.key:}") String base64Key, PolicyEngine policies) {
        boolean needed = policies.policies().values().stream().anyMatch(CategoryPolicy::encrypted);
        byte[] key = decodeKey(base64Key);
        if (key == null && needed) {
            throw new IllegalStateException("DOC_ENCRYPTION_KEY lipsește sau nu este base64 de 32 de octeți, iar "
                    + "politica cere criptare (encryption: true).");
        }
        this.master = key;
    }

    /** For tests: the raw key (32 bytes). */
    public AesGcmDocumentEncryption(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Cheia AES-256 trebuie să aibă 32 de octeți");
        }
        this.master = key.clone();
    }

    private static byte[] decodeKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(base64.strip());
            return key.length == 32 ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public long encryptedLength(long plainLength) {
        long segments = Math.max(1, (plainLength + SEGMENT - 1) / SEGMENT);
        return HEADER + plainLength + segments * TAG;
    }

    @Override
    public InputStream encrypt(InputStream plain) {
        requireKey();
        byte[] header = new byte[HEADER];
        header[0] = VERSION;
        byte[] random = new byte[SALT + PREFIX];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, header, 1, random.length);
        return new EncryptingStream(plain, header, fileKey(Arrays.copyOfRange(header, 1, 1 + SALT)),
                Arrays.copyOfRange(header, 1 + SALT, HEADER));
    }

    @Override
    public InputStream decrypt(InputStream stored) {
        requireKey();
        return new DecryptingStream(stored);
    }

    @Override
    public String encryptName(String name) {
        requireKey();
        byte[] bytes = truncateUtf8(name == null ? "" : name, NAME_MAX_BYTES);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derive(NAME_CONTEXT, new byte[0]), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(new byte[] {VERSION});
            byte[] ct = cipher.doFinal(bytes);
            ByteBuffer out = ByteBuffer.allocate(1 + nonce.length + ct.length);
            out.put((byte) VERSION).put(nonce).put(ct);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Criptarea numelui a eșuat", e);
        }
    }

    @Override
    public String decryptName(String stored) throws CorruptCiphertextException {
        requireKey();
        try {
            byte[] in = Base64.getUrlDecoder().decode(stored);
            if (in.length < 1 + 12 + TAG || in[0] != VERSION) {
                throw new CorruptCiphertextException("Nume criptat invalid");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derive(NAME_CONTEXT, new byte[0]), new GCMParameterSpec(128, in, 1, 12));
            cipher.updateAAD(new byte[] {VERSION});
            return new String(cipher.doFinal(in, 13, in.length - 13), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CorruptCiphertextException("Nume criptat invalid");
        }
    }

    private void requireKey() {
        if (master == null) {
            throw new IllegalStateException("Criptarea documentelor nu are cheie (DOC_ENCRYPTION_KEY)");
        }
    }

    private SecretKey fileKey(byte[] salt) {
        return derive(FILE_CONTEXT, salt);
    }

    private SecretKey derive(byte[] context, byte[] salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            mac.update(context);
            mac.update(salt);
            return new SecretKeySpec(mac.doFinal(), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] truncateUtf8(String s, int max) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) {
            return bytes;
        }
        int end = max;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) { // do not cut in the middle of a character
            end--;
        }
        return Arrays.copyOf(bytes, end);
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] prefix, long index, boolean last)
            throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(prefix).putLong(index).array();
        byte[] aad = ByteBuffer.allocate(10).put((byte) VERSION).putLong(index).put((byte) (last ? 1 : 0)).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    /** Reads up to {@code len} bytes (or until EOF). */
    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    /** Base for both streams: current segment in {@code out}, the next one is produced on demand. */
    private abstract static class SegmentStream extends InputStream {
        byte[] out;
        int pos;
        long index;
        boolean finished;

        abstract boolean fill() throws IOException;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (pos >= out.length) {
                if (!fill()) {
                    return -1;
                }
            }
            int n = Math.min(len, out.length - pos);
            System.arraycopy(out, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** Encrypted segments; a full segment is held until we know whether more data follows (so it can be flagged "last"). */
    private static final class EncryptingStream extends SegmentStream {
        private final InputStream plain;
        private final SecretKey key;
        private final byte[] prefix;
        private final byte[] segment = new byte[SEGMENT];
        private int carry = -1;

        EncryptingStream(InputStream plain, byte[] header, SecretKey key, byte[] prefix) {
            this.plain = plain;
            this.key = key;
            this.prefix = prefix;
            this.out = header; // the header goes out first
        }

        @Override
        boolean fill() throws IOException {
            if (finished) {
                return false;
            }
            int n = 0;
            if (carry >= 0) {
                segment[0] = (byte) carry;
                n = 1;
                carry = -1;
            }
            n += readFully(plain, segment, n, SEGMENT - n);
            boolean last = true;
            if (n == SEGMENT) {
                int next = plain.read();
                if (next >= 0) {
                    carry = next;
                    last = false;
                }
            }
            try {
                out = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, last).doFinal(segment, 0, n);
            } catch (GeneralSecurityException e) {
                throw new IOException("Criptarea segmentului a eșuat", e);
            }
            pos = 0;
            finished = last;
            return true;
        }

        @Override
        public void close() throws IOException {
            plain.close();
        }
    }

    private final class DecryptingStream extends SegmentStream {
        private final InputStream stored;
        private final byte[] enc = new byte[SEGMENT + TAG];
        private SecretKey key;
        private byte[] prefix;
        private int pending = -1;

        DecryptingStream(InputStream stored) {
            this.stored = stored;
            this.out = new byte[0];
        }

        @Override
        boolean fill() throws IOException {
            if (finished) {
                return false;
            }
            if (key == null) {
                byte[] header = new byte[HEADER];
                if (readFully(stored, header, 0, HEADER) != HEADER || header[0] != VERSION) {
                    throw new CorruptCiphertextException("Antet de criptare invalid sau trunchiat");
                }
                key = fileKey(Arrays.copyOfRange(header, 1, 1 + SALT));
                prefix = Arrays.copyOfRange(header, 1 + SALT, HEADER);
            }
            int n = 0;
            if (pending >= 0) {
                enc[0] = (byte) pending;
                n = 1;
                pending = -1;
            }
            n += readFully(stored, enc, n, enc.length - n);
            boolean last = true;
            if (n == enc.length) {
                int next = stored.read();
                if (next >= 0) {
                    pending = next;
                    last = false;
                }
            }
            if (n < TAG) {
                throw new CorruptCiphertextException("Flux criptat trunchiat");
            }
            try {
                out = cipher(Cipher.DECRYPT_MODE, key, prefix, index++, last).doFinal(enc, 0, n);
            } catch (GeneralSecurityException e) {
                throw new CorruptCiphertextException("Segment alterat, reordonat sau trunchiat (index " + (index - 1) + ")");
            }
            pos = 0;
            finished = last;
            return true;
        }

        @Override
        public void close() throws IOException {
            stored.close();
        }
    }
}
