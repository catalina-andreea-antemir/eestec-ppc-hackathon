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
 * AES-256-GCM pe segmente de 64 KiB, în streaming (4.1 „Criptare și hash la documente”). Cheia vine din
 * {@code DOC_ENCRYPTION_KEY} (base64, 32 de octeți) și nu se loghează. Aplicația refuză să pornească cu cheie lipsă
 * sau invalidă doar dacă politica are cel puțin o categorie cu {@code encryption: true}.
 *
 * <p>Format: {@code [versiune 1B][salt 16B][prefix nonce 4B]} apoi segmentele {@code [ciphertext + tag 16B]}. Cheia
 * fiecărui fișier se derivă din cheia principală și salt (HMAC-SHA256), deci o coliziune a prefixului de 4 octeți între
 * fișiere nu refolosește perechea (cheie, nonce). Nonce = prefix + contor de segment pe 8 octeți; AAD = versiunea,
 * indexul segmentului și marcajul „ultimul segment”, deci reordonarea sau trunchierea fac tag-ul să cadă. Fiecare
 * segment e plin (64 KiB) în afară de ultimul; un fișier gol are un singur segment (doar tag).
 */
@Component
public class AesGcmDocumentEncryption implements DocumentEncryption {

    public static final int SEGMENT = 64 * 1024;
    static final int VERSION = 1;
    static final int SALT = 16;
    static final int PREFIX = 4;
    static final int HEADER = 1 + SALT + PREFIX;
    static final int TAG = 16;
    /** Numele: versiune 1B + nonce 12B + text + tag, în base64; cel mult 350 de octeți UTF-8 încap în NVARCHAR(512). */
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

    /** Pentru teste: cheia brută (32 de octeți). */
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
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) { // nu tăia în mijlocul unui caracter
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

    /** Citește până la {@code len} octeți (sau EOF). */
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

    /** Baza celor două fluxuri: segmentul curent în {@code out}, următorul se produce la cerere. */
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

    /** Segmentele cifrate; un segment plin se ține până se știe dacă mai urmează date (ca să fie marcat „ultimul”). */
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
            this.out = header; // antetul iese primul
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
