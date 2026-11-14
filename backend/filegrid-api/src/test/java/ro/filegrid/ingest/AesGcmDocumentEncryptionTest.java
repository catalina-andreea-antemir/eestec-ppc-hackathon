package ro.filegrid.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.filegrid.ingest.DocumentEncryption.CorruptCiphertextException;

class AesGcmDocumentEncryptionTest {

    private static final int SEG = AesGcmDocumentEncryption.SEGMENT;
    private static final int FULL = SEG + AesGcmDocumentEncryption.TAG;
    private static final int HEADER = AesGcmDocumentEncryption.HEADER;

    private final AesGcmDocumentEncryption enc = new AesGcmDocumentEncryption(key(1));

    private static byte[] key(int seed) {
        byte[] k = new byte[32];
        new Random(seed).nextBytes(k);
        return k;
    }

    private static byte[] data(int n) {
        byte[] d = new byte[n];
        new Random(n).nextBytes(d);
        return d;
    }

    private byte[] encrypt(byte[] plain) throws IOException {
        return enc.encrypt(new ByteArrayInputStream(plain)).readAllBytes();
    }

    private byte[] decrypt(byte[] stored) throws IOException {
        return enc.decrypt(new ByteArrayInputStream(stored)).readAllBytes();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 100, SEG - 1, SEG, SEG + 1, 3 * SEG, 3 * SEG + 17})
    void roundTripAndLengthFormula(int size) throws IOException {
        byte[] plain = data(size);
        byte[] stored = encrypt(plain);

        assertThat(stored).hasSize((int) enc.encryptedLength(size));
        assertThat(decrypt(stored)).isEqualTo(plain);
    }

    @Test
    void ciphertextDoesNotContainPlaintext() throws IOException {
        byte[] plain = "NUME-CLIENT-SECRET-Ionescu Maria".repeat(50).getBytes(StandardCharsets.UTF_8);
        String raw = new String(encrypt(plain), StandardCharsets.ISO_8859_1);

        assertThat(raw).doesNotContain("Ionescu").doesNotContain("SECRET");
    }

    @Test
    void sameContentGivesDifferentNoncesAndCiphertext() throws IOException {
        byte[] plain = data(SEG + 10);
        byte[] a = encrypt(plain);
        byte[] b = encrypt(plain);

        assertThat(Arrays.copyOfRange(a, 17, HEADER)).isNotEqualTo(Arrays.copyOfRange(b, 17, HEADER)); // prefix nonce
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void alteredByteInAnySegmentIsDetected() throws IOException {
        byte[] stored = encrypt(data(3 * SEG + 5));
        for (int pos : new int[] {HEADER + 3, HEADER + FULL + 9, stored.length - 1, 2}) {
            byte[] bad = stored.clone();
            bad[pos] ^= 1;
            assertThatThrownBy(() -> decrypt(bad)).isInstanceOf(CorruptCiphertextException.class);
        }
    }

    @Test
    void reorderedSegmentsAreDetected() throws IOException {
        byte[] stored = encrypt(data(3 * SEG + 5));
        byte[] swapped = stored.clone();
        System.arraycopy(stored, HEADER + FULL, swapped, HEADER, FULL);
        System.arraycopy(stored, HEADER, swapped, HEADER + FULL, FULL);

        assertThatThrownBy(() -> decrypt(swapped)).isInstanceOf(CorruptCiphertextException.class);
    }

    @Test
    void truncationIsDetectedAtSegmentBoundaryAndMidSegment() throws IOException {
        byte[] stored = encrypt(data(3 * SEG + 5));

        assertThatThrownBy(() -> decrypt(Arrays.copyOf(stored, HEADER + 2 * FULL)))
                .isInstanceOf(CorruptCiphertextException.class);
        assertThatThrownBy(() -> decrypt(Arrays.copyOf(stored, stored.length - 3)))
                .isInstanceOf(CorruptCiphertextException.class);
        assertThatThrownBy(() -> decrypt(Arrays.copyOf(stored, 10))).isInstanceOf(CorruptCiphertextException.class);
        assertThatThrownBy(() -> decrypt(new byte[0])).isInstanceOf(CorruptCiphertextException.class);
    }

    @Test
    void appendedDataIsDetected() throws IOException {
        byte[] stored = encrypt(data(SEG));
        byte[] longer = Arrays.copyOf(stored, stored.length + 20);

        assertThatThrownBy(() -> decrypt(longer)).isInstanceOf(CorruptCiphertextException.class);
    }

    @Test
    void wrongKeyFailsAuthentication() throws IOException {
        byte[] stored = encrypt(data(1000));
        AesGcmDocumentEncryption other = new AesGcmDocumentEncryption(key(2));

        assertThatThrownBy(() -> other.decrypt(new ByteArrayInputStream(stored)).readAllBytes())
                .isInstanceOf(CorruptCiphertextException.class);
    }

    @Test
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> new AesGcmDocumentEncryption(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmDocumentEncryption((byte[]) null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupFailsWithMissingOrBadKeyWhenPolicyEncrypts() {
        var policies = new ro.filegrid.policy.PolicyEngine("classpath:/policies/", java.time.Duration.ofHours(1));
        String good = Base64.getEncoder().encodeToString(key(3));

        assertThat(new AesGcmDocumentEncryption(good, policies)).isNotNull();
        for (String bad : new String[] {"", "  ", "nu-e-base64!!", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> new AesGcmDocumentEncryption(bad, policies))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DOC_ENCRYPTION_KEY");
        }
    }

    @Test
    void nameRoundTripFitsColumnAndIsRandomized() throws IOException {
        String name = "Cerere racordare Ștefan Țărnă.pdf";
        String a = enc.encryptName(name);
        String b = enc.encryptName(name);

        assertThat(enc.decryptName(a)).isEqualTo(name);
        assertThat(a).isNotEqualTo(b).doesNotContain("Cerere");
        assertThat(enc.encryptName("ș".repeat(255)).length()).isLessThanOrEqualTo(512);
        assertThatThrownBy(() -> enc.decryptName(a.substring(0, a.length() - 2) + "AA"))
                .isInstanceOf(CorruptCiphertextException.class);
        assertThatThrownBy(() -> enc.decryptName("nume-in-clar.pdf")).isInstanceOf(CorruptCiphertextException.class);
    }

    @Test
    void streamsInSmallReads() throws IOException {
        byte[] plain = data(5 * SEG + 1);
        var in = enc.encrypt(new ByteArrayInputStream(plain));
        byte[] chunk = new byte[1000];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            total += n;
        }
        assertThat(total).isEqualTo(enc.encryptedLength(plain.length));
    }
}
