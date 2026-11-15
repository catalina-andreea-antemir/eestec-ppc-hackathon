package ro.filegrid.integrity;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streaming SHA-256 (C7): the stream is read once, by the storage layer, and the hash is available at the end. */
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

    /** Wraps the source; take the hash with {@code stream.getMessageDigest().digest()} once it has been read. */
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
