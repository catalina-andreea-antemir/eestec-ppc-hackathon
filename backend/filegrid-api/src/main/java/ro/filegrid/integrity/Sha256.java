package ro.filegrid.integrity;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 calculat în streaming (C7): fluxul se citește o singură dată, de storage, iar hash-ul iese la final. */
public final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Învelește sursa; hash-ul se ia cu {@code stream.getMessageDigest().digest()} după ce a fost citită. */
    public static DigestInputStream digesting(InputStream source) {
        return new DigestInputStream(source, newDigest());
    }

    public static byte[] of(byte[] data) {
        return newDigest().digest(data);
    }

    public static String hex(byte[] hash) {
        return hash == null ? null : HEX.formatHex(hash);
    }
}
