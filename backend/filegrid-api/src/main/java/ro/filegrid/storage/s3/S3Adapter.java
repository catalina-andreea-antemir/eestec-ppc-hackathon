package ro.filegrid.storage.s3;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.ChunkReceipt;
import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.ChunkedWriter;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageUnavailableException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link StorageAdapter} and {@link ChunkedWriter} on MinIO (S3), using AWS SDK v2 (C5, ADR-003).
 *
 * <ul>
 *   <li><b>Client</b>: path-style (MinIO), pooled Apache 5 HTTP client, short timeouts (connect 2 s, socket 30 s,
 *       small operations 10 s, {@code health} 2 s) and <b>no retry</b> in the adapter: resilience (retry with
 *       backoff, circuit breaker, fallback) is layered on top (C11). SDK CRC checksums are only sent when the
 *       operation requires them ({@code WHEN_REQUIRED}) so MinIO gets classic S3 requests; integrity is guaranteed
 *       by the caller's SHA-256.</li>
 *   <li><b>{@code put}</b>: streamed, no object-sized buffer. Up to {@code partSize} (16 MiB) a single
 *       {@code PutObject}; above that, internal multipart with fixed {@code partSize} parts (the last one smaller),
 *       aborted ({@code AbortMultipartUpload}) on any error so the object never shows up half-written.</li>
 *   <li><b>{@code get}</b>: {@code GetObject} with {@code Range: bytes=a-b} (S3 range GET); an offset past the end
 *       (416 on S3) yields an empty stream. The returned stream abandons the connection on {@code close()} if it was
 *       not read to the end (it does not download the rest just to reuse the connection).</li>
 *   <li><b>Chunked upload</b>: {@code open} = {@code CreateMultipartUpload} (reference = {@code UploadId}),
 *       {@code append(n)} = {@code UploadPart} with {@code partNumber = n} (a resend replaces the part),
 *       {@code finish} = {@code CompleteMultipartUpload} with the ETags in order (idempotent), {@code abort} =
 *       {@code AbortMultipartUpload} (idempotent) plus deleting the object if the upload had already completed.</li>
 *   <li><b>Errors</b>: network / timeout / HTTP 5xx / 429 -> {@link StorageUnavailableException}; missing key ->
 *       {@link StorageObjectNotFoundException}; a short or broken caller source and other S3 errors (403, missing
 *       bucket, etc.) -> {@link StorageException}. Messages include the operation, HTTP status and S3 error code,
 *       never credentials.</li>
 * </ul>
 *
 * No per-request state in fields: one instance is used concurrently.
 */
public class S3Adapter implements StorageAdapter, ChunkedWriter, AutoCloseable {

    /** Minimum part size on S3 / MinIO (except the last part). */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;

    /** Maximum number of parts in a multipart upload (S3 limit). */
    public static final int MAX_PARTS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(S3Adapter.class);
    private static final StorageType TYPE = StorageType.S3;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final int MAX_DETAIL = 200;

    private final S3Client client;
    private final String bucket;
    private final long partSize;
    private final Duration operationTimeout;
    private final Duration healthTimeout;
    /** Why the adapter is not configured (names of the missing variables), or {@code null}. */
    private final String notConfigured;

    /**
     * @param client           the S3 client (closed by the adapter in {@link #close()})
     * @param bucket           the bucket
     * @param partSize         part size for multipart {@code put} (at least 5 MiB)
     * @param operationTimeout total timeout for small operations
     * @param healthTimeout    total timeout for {@link #health()} (at most 2 s)
     */
    public S3Adapter(S3Client client, String bucket, long partSize, Duration operationTimeout, Duration healthTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = requireText(bucket, "bucket");
        if (partSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("partSize trebuie să fie de cel puțin 5 MiB");
        }
        this.partSize = partSize;
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        this.healthTimeout = Objects.requireNonNull(healthTimeout, "healthTimeout");
        this.notConfigured = null;
    }

    private S3Adapter(String notConfigured) {
        this.client = null;
        this.bucket = null;
        this.partSize = MIN_PART_SIZE;
        this.operationTimeout = Duration.ZERO;
        this.healthTimeout = Duration.ZERO;
        this.notConfigured = notConfigured;
    }

    /**
     * Adapter built from configuration. If variables ({@code S3_*}) are missing, returns an unconfigured adapter: the
     * app starts, {@link #health()} is down and operations throw {@link StorageUnavailableException}.
     */
    public static S3Adapter fromProperties(S3Properties properties) {
        if (!properties.isConfigured()) {
            String reason = "S3 neconfigurat (lipsesc " + properties.missing() + ")";
            log.warn("{}: S3 storage will be reported as unavailable", reason);
            return new S3Adapter(reason);
        }
        S3Adapter adapter = new S3Adapter(buildClient(properties), properties.bucket(),
                properties.partSize().toBytes(), properties.operationTimeout(), properties.healthTimeout());
        log.info("S3Adapter: endpoint {}, bucket {}, {} MiB parts", properties.endpoint(), properties.bucket(),
                properties.partSize().toMegabytes());
        return adapter;
    }

    /** S3 client for MinIO: path-style, Apache 5, short timeouts, no retry. */
    public static S3Client buildClient(S3Properties p) {
        if (!p.isConfigured()) {
            throw new IllegalArgumentException("S3 neconfigurat (lipsesc " + p.missing() + ")");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(p.endpoint().trim()))
                .region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(p.connectTimeout())
                        .socketTimeout(p.socketTimeout())
                        .connectionAcquisitionTimeout(p.connectTimeout())
                        .connectionMaxIdleTime(Duration.ofSeconds(10))
                        .maxConnections(p.maxConnections()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .build())
                .build();
    }

    @Override
    public StorageType type() {
        return TYPE;
    }

    // --- StorageAdapter ---------------------------------------------------------------------------------------

    @Override
    public long put(String key, InputStream data, long length, String contentType) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(data, "data");
        if (length <= 0) {
            throw new IllegalArgumentException("length trebuie să fie pozitiv");
        }
        S3Client s3 = client();
        String type = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        BoundedSource source = new BoundedSource(data, length);
        if (length <= partSize) {
            try {
                s3.putObject(b -> b.bucket(bucket).key(key).contentLength(length).contentType(type),
                        RequestBody.fromInputStream(source, length));
            } catch (SdkException e) {
                throw translate("PutObject", key, e, source);
            }
        } else {
            putMultipart(s3, key, source, length, type);
        }
        return length;
    }

    private void putMultipart(S3Client s3, String key, BoundedSource source, long length, String type) {
        long parts = (length + partSize - 1) / partSize;
        if (parts > MAX_PARTS) {
            throw new IllegalArgumentException("Obiect prea mare pentru părți de " + partSize + " octeți");
        }
        String uploadId = createMultipart(s3, key, type);
        try {
            List<CompletedPart> completed = new ArrayList<>((int) parts);
            for (int n = 1; n <= parts; n++) {
                long partLength = Math.min(partSize, length - (n - 1) * partSize);
                String etag = uploadPart(s3, key, uploadId, n, source.slice(partLength), partLength, source);
                completed.add(CompletedPart.builder().partNumber(n).eTag(etag).build());
            }
            try {
                s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(m -> m.parts(completed)).overrideConfiguration(small()));
            } catch (SdkException e) {
                throw translate("CompleteMultipartUpload", key, e, null);
            }
        } catch (RuntimeException e) {
            abortQuietly(s3, key, uploadId);
            throw e;
        }
    }

    @Override
    public InputStream get(String key, ByteRange range) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(range, "range");
        S3Client s3 = client();
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (!range.isFull()) {
            request.range(rangeHeader(range));
        }
        try {
            return new ObjectStream(s3.getObject(request.build()));
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                // Offset past the end of the object (the key exists; otherwise S3 returns 404): empty stream, per the contract.
                return InputStream.nullInputStream();
            }
            throw translate("GetObject", key, e, null);
        } catch (SdkException e) {
            throw translate("GetObject", key, e, null);
        }
    }

    @Override
    public boolean delete(String key) {
        StorageKeys.requireValid(key);
        S3Client s3 = client();
        boolean existed = exists(key);
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key).overrideConfiguration(small()));
        } catch (SdkException e) {
            throw translate("DeleteObject", key, e, null);
        }
        int aborted = abortPendingUploads(s3, key);
        return existed || aborted > 0;
    }

    @Override
    public boolean exists(String key) {
        StorageKeys.requireValid(key);
        return head(client(), key) != null;
    }

    @Override
    public StorageHealth health() {
        long start = System.nanoTime();
        if (notConfigured != null) {
            return StorageHealth.down(TYPE, Duration.ZERO, notConfigured);
        }
        try {
            client.headBucket(b -> b.bucket(bucket)
                    .overrideConfiguration(o -> o.apiCallTimeout(healthTimeout).apiCallAttemptTimeout(healthTimeout)));
            return StorageHealth.up(TYPE, Duration.ofNanos(System.nanoTime() - start));
        } catch (RuntimeException e) {
            return StorageHealth.down(TYPE, Duration.ofNanos(System.nanoTime() - start), detail(e));
        }
    }

    // --- ChunkedWriter ----------------------------------------------------------------------------------------

    @Override
    public String open(String key, long totalSize, int chunkSize) {
        StorageKeys.requireValid(key);
        if (totalSize <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException("totalSize și chunkSize trebuie să fie pozitive");
        }
        long chunks = (totalSize + chunkSize - 1) / chunkSize;
        if (chunks > 1 && chunkSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("Pe S3 chunkSize este de cel puțin 5 MiB (cu excepția ultimei bucăți)");
        }
        if (chunks > MAX_PARTS) {
            throw new IllegalArgumentException("Pe S3 un upload are cel mult " + MAX_PARTS + " de bucăți");
        }
        String uploadId = createMultipart(client(), key, DEFAULT_CONTENT_TYPE);
        if (uploadId == null || uploadId.isEmpty() || uploadId.length() > 1024) {
            abortQuietly(client, key, uploadId);
            throw new StorageException(TYPE, "S3 CreateMultipartUpload: UploadId invalid pentru " + key);
        }
        return uploadId;
    }

    @Override
    public ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(data, "data");
        upload.requireChunkNumber(n);
        if (length != upload.expectedLength(n)) {
            throw new IllegalArgumentException("Bucata " + n + " are " + upload.expectedLength(n) + " octeți, nu "
                    + length);
        }
        BoundedSource source = new BoundedSource(data, length);
        String etag = uploadPart(client(), upload.key(), upload.uploadRef(), n, source, length, source);
        return new ChunkReceipt(n, length, etag);
    }

    @Override
    public long finish(ChunkedUpload upload, List<ChunkReceipt> chunks) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(chunks, "chunks");
        S3Client s3 = client();
        List<CompletedPart> parts = completedParts(upload, chunks);
        try {
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadRef())
                    .multipartUpload(m -> m.parts(parts)).overrideConfiguration(small()));
        } catch (S3Exception e) {
            if (!isNoSuchUpload(e)) {
                throw translate("CompleteMultipartUpload", upload.key(), e, null);
            }
            // The upload no longer exists: if the object is already complete, this is a second finish (idempotent).
            log.debug("CompleteMultipartUpload: NoSuchUpload for {}, checking the object", upload.key());
        } catch (SdkException e) {
            throw translate("CompleteMultipartUpload", upload.key(), e, null);
        }
        HeadObjectResponse object = head(s3, upload.key());
        if (object == null) {
            throw new StorageException(TYPE, "S3 CompleteMultipartUpload: upload-ul " + upload.key()
                    + " nu mai există (abandonat) și obiectul lipsește");
        }
        if (object.contentLength() == null || object.contentLength() != upload.totalSize()) {
            throw new StorageException(TYPE, "S3 CompleteMultipartUpload: obiectul " + upload.key() + " are "
                    + object.contentLength() + " octeți, nu " + upload.totalSize());
        }
        return upload.totalSize();
    }

    @Override
    public void abort(ChunkedUpload upload) {
        Objects.requireNonNull(upload, "upload");
        S3Client s3 = client();
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadRef())
                    .overrideConfiguration(small()));
        } catch (S3Exception e) {
            if (!isNoSuchUpload(e)) {
                throw translate("AbortMultipartUpload", upload.key(), e, null);
            }
        } catch (SdkException e) {
            throw translate("AbortMultipartUpload", upload.key(), e, null);
        }
        // "Delete everything written": if finish had already succeeded (but the session was not confirmed), the final
        // object would be orphaned; the key is unique per session, so deleting it is safe. Idempotent.
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(upload.key()).overrideConfiguration(small()));
        } catch (SdkException e) {
            throw translate("DeleteObject", upload.key(), e, null);
        }
    }

    /** Closes the S3 client (and its HTTP pool); Spring calls it when the context shuts down. */
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    // --- S3 helpers -------------------------------------------------------------------------------------------

    private S3Client client() {
        if (client == null) {
            throw new StorageUnavailableException(TYPE, notConfigured);
        }
        return client;
    }

    private Consumer<AwsRequestOverrideConfiguration.Builder> small() {
        return o -> o.apiCallTimeout(operationTimeout);
    }

    private String createMultipart(S3Client s3, String key, String contentType) {
        try {
            return s3.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType(contentType)
                    .overrideConfiguration(small())).uploadId();
        } catch (SdkException e) {
            throw translate("CreateMultipartUpload", key, e, null);
        }
    }

    private String uploadPart(S3Client s3, String key, String uploadId, int n, InputStream body, long length,
            BoundedSource source) {
        try {
            String etag = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(n)
                    .contentLength(length), RequestBody.fromInputStream(body, length)).eTag();
            if (etag == null || etag.isEmpty() || etag.length() > 255) {
                throw new StorageException(TYPE, "S3 UploadPart: ETag invalid pentru partea " + n + " a " + key);
            }
            return etag;
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) {
                throw new StorageException(TYPE, "S3 UploadPart: upload-ul " + key
                        + " nu mai există (abandonat sau încheiat)", e);
            }
            throw translate("UploadPart", key, e, source);
        } catch (SdkException e) {
            throw translate("UploadPart", key, e, source);
        }
    }

    /** {@code null} if the object does not exist. */
    private HeadObjectResponse head(S3Client s3, String key) {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key).overrideConfiguration(small()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404 && !isNoSuchBucket(e)) {
                return null;
            }
            throw translate("HeadObject", key, e, null);
        } catch (SdkException e) {
            throw translate("HeadObject", key, e, null);
        }
    }

    /** Aborts unfinished multipart uploads for exactly this key; returns how many it found. */
    private int abortPendingUploads(S3Client s3, String key) {
        int aborted = 0;
        String keyMarker = null;
        String uploadIdMarker = null;
        try {
            while (true) {
                String km = keyMarker;
                String um = uploadIdMarker;
                ListMultipartUploadsResponse page = s3.listMultipartUploads(b -> b.bucket(bucket).prefix(key)
                        .keyMarker(km).uploadIdMarker(um).overrideConfiguration(small()));
                for (MultipartUpload upload : page.uploads()) {
                    if (key.equals(upload.key())) {
                        abortQuietly(s3, key, upload.uploadId());
                        aborted++;
                    }
                }
                if (!Boolean.TRUE.equals(page.isTruncated())) {
                    return aborted;
                }
                keyMarker = page.nextKeyMarker();
                uploadIdMarker = page.nextUploadIdMarker();
            }
        } catch (SdkException e) {
            throw translate("ListMultipartUploads", key, e, null);
        }
    }

    private void abortQuietly(S3Client s3, String key, String uploadId) {
        if (uploadId == null || uploadId.isEmpty()) {
            return;
        }
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId).overrideConfiguration(small()));
        } catch (RuntimeException e) {
            // Left for session cleanup (24 h, C7) or MinIO's own cleanup (stale_uploads_expiry, 24 h by default);
            // the original error is the one that matters.
            log.warn("AbortMultipartUpload failed for {}: {}", key, detail(e));
        }
    }

    private List<CompletedPart> completedParts(ChunkedUpload upload, List<ChunkReceipt> chunks) {
        int total = upload.totalChunks();
        if (chunks.size() != total) {
            throw new IllegalArgumentException("finish are nevoie de toate cele " + total + " bucăți, nu "
                    + chunks.size());
        }
        List<CompletedPart> parts = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            ChunkReceipt receipt = Objects.requireNonNull(chunks.get(i), "chunk");
            int n = i + 1;
            if (receipt.n() != n || receipt.etag() == null || receipt.size() != upload.expectedLength(n)) {
                throw new IllegalArgumentException("Confirmarea bucății " + n + " lipsește sau e invalidă");
            }
            parts.add(CompletedPart.builder().partNumber(n).eTag(receipt.etag()).build());
        }
        return parts;
    }

    static String rangeHeader(ByteRange range) {
        if (range.isToEnd()) {
            return "bytes=" + range.offset() + "-";
        }
        long last = range.offset() + range.length() - 1;
        return last < range.offset() ? "bytes=" + range.offset() + "-" : "bytes=" + range.offset() + "-" + last;
    }

    private static boolean isNoSuchUpload(S3Exception e) {
        return e.statusCode() == 404 && !isNoSuchBucket(e);
    }

    private static boolean isNoSuchBucket(S3Exception e) {
        return "NoSuchBucket".equals(errorCode(e));
    }

    private static String errorCode(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    /**
     * SDK exception -> contract exception. {@code source} (if present) tells whether the error came from the caller's
     * source rather than from storage.
     */
    private static StorageException translate(String operation, String key, SdkException e, BoundedSource source) {
        if (source != null && source.failure() != null) {
            return new StorageException(TYPE, "S3 " + operation + " " + key + ": " + source.failure(), e);
        }
        if (e instanceof S3Exception s3) {
            String message = "S3 " + operation + " " + key + ": HTTP " + s3.statusCode()
                    + (errorCode(s3) == null ? "" : " " + errorCode(s3));
            if (s3.statusCode() == 404 && "GetObject".equals(operation) && !isNoSuchBucket(s3)) {
                return new StorageObjectNotFoundException(TYPE, key);
            }
            if (s3.statusCode() >= 500 || s3.statusCode() == 429) {
                return new StorageUnavailableException(TYPE, message, e);
            }
            return new StorageException(TYPE, message, e);
        }
        if (e instanceof SdkClientException) {
            // Connection refused / dropped, timeout (ApiCallTimeoutException is also an SdkClientException).
            return new StorageUnavailableException(TYPE, "S3 " + operation + " " + key + ": " + detail(e), e);
        }
        return new StorageException(TYPE, "S3 " + operation + " " + key + ": " + detail(e), e);
    }

    /** Short reason without credentials (the SDK never puts keys in messages; HTTP status and S3 code are kept). */
    private static String detail(Throwable e) {
        String text;
        if (e instanceof S3Exception s3) {
            text = "HTTP " + s3.statusCode() + (errorCode(s3) == null ? "" : " " + errorCode(s3));
        } else {
            text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) : text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " lipsește");
        }
        return value;
    }

    // --- streams ----------------------------------------------------------------------------------------------

    /**
     * The caller's source capped at exactly {@code length} bytes: never reads more (not even ahead), and if the source
     * ends early or throws, the reason is recorded so the error is not mistaken for a storage outage.
     * {@code close()} does not close the source (the caller owns it).
     */
    static final class BoundedSource extends InputStream {

        private final InputStream in;
        private final long length;
        private long read;
        private volatile String failure;

        BoundedSource(InputStream in, long length) {
            this.in = in;
            this.length = length;
        }

        String failure() {
            return failure;
        }

        /** The next {@code partLength} bytes as a separate stream (for one multipart part). */
        InputStream slice(long partLength) {
            BoundedSource parent = this;
            return new InputStream() {
                private long left = partLength;

                @Override
                public int read() throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int b = parent.read();
                    if (b >= 0) {
                        left--;
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int r = parent.read(buf, off, (int) Math.min(len, left));
                    if (r > 0) {
                        left -= r;
                    }
                    return r;
                }
            };
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, buf.length);
            if (len == 0) {
                return 0;
            }
            long left = length - read;
            if (left <= 0) {
                return -1;
            }
            int r;
            try {
                r = in.read(buf, off, (int) Math.min(len, left));
            } catch (IOException e) {
                failure = "sursa a fost întreruptă după " + read + " din " + length + " octeți";
                throw e;
            }
            if (r < 0) {
                failure = "sursa s-a terminat după " + read + " din " + length + " octeți";
                throw new IOException(failure);
            }
            read += r;
            return r;
        }

        @Override
        public void close() {
            // The caller closes the source.
        }
    }

    /**
     * A {@code GetObject} body: SDK errors during reading surface as {@link IOException}, and {@code close()} before the
     * end abandons the connection instead of draining it (matters for small Ranges of large objects and for HTTP
     * clients that give up).
     */
    static final class ObjectStream extends FilterInputStream {

        private final ResponseInputStream<GetObjectResponse> response;
        private boolean eof;
        private boolean closed;

        ObjectStream(ResponseInputStream<GetObjectResponse> response) {
            super(response);
            this.response = response;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                eof |= b < 0;
                return b;
            } catch (SdkException e) {
                throw new IOException("S3: citire întreruptă: " + detail(e), e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int r = super.read(b, off, len);
                eof |= r < 0;
                return r;
            } catch (SdkException e) {
                throw new IOException("S3: citire întreruptă: " + detail(e), e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (!eof) {
                response.abort();
            }
            super.close();
        }
    }
}
