package ro.filegrid.storage;

/**
 * Confirmation of a chunk written by {@link ChunkedWriter#append}; the caller stores it in {@code upload_chunks}
 * ({@code n}, {@code size_bytes}, {@code etag}) and passes it back to {@link ChunkedWriter#finish}.
 *
 * @param n    chunk number, starting at 1
 * @param size bytes written
 * @param etag S3 part ETag (required for S3 {@code CompleteMultipartUpload}); {@code null} for FTP and DB if they
 *             do not need it (at most 255 characters, {@code upload_chunks.etag})
 */
public record ChunkReceipt(int n, long size, String etag) {

    public ChunkReceipt {
        if (n < 1) {
            throw new IllegalArgumentException("n pornește de la 1");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("O bucată are cel puțin un octet");
        }
        if (etag != null && (etag.isEmpty() || etag.length() > 255)) {
            throw new IllegalArgumentException("etag are 1–255 de caractere sau lipsește");
        }
    }
}
