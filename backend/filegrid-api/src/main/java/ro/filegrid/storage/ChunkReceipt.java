package ro.filegrid.storage;

/**
 * Confirmarea unei bucăți scrise de {@link ChunkedWriter#append}; apelantul o salvează în {@code upload_chunks}
 * ({@code n}, {@code size_bytes}, {@code etag}) și o dă înapoi la {@link ChunkedWriter#finish}.
 *
 * @param n    numărul bucății, de la 1
 * @param size octeții scriși
 * @param etag ETag-ul S3 al părții (obligatoriu la S3, pentru {@code CompleteMultipartUpload}); {@code null} la FTP și
 *             DB, dacă nu au nevoie de el (maxim 255 de caractere, {@code upload_chunks.etag})
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
