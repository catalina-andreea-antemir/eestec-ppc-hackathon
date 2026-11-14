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
 * {@link StorageAdapter} și {@link ChunkedWriter} pe MinIO (S3), cu AWS SDK v2 (C5; planul v4 4.1, ADR-003).
 *
 * <ul>
 *   <li><b>Client</b>: path-style (MinIO), client HTTP Apache 5 cu pool, timeout-uri scurte (conectare 2 s, socket 30 s,
 *       operațiile mici 10 s, {@code health} 2 s) și <b>fără retry</b> în adaptor: reziliența (retry cu backoff, circuit
 *       breaker, fallback) vine deasupra, la C11. Sumele de control CRC ale SDK-ului se trimit doar când operația le
 *       cere ({@code WHEN_REQUIRED}), ca MinIO să primească cereri S3 clasice; integritatea o asigură SHA-256 al
 *       apelantului.</li>
 *   <li><b>{@code put}</b>: în streaming, fără buffer de dimensiunea obiectului. Până la {@code partSize} (16 MiB)
 *       un singur {@code PutObject}; peste, multipart intern cu părți fixe de {@code partSize} (ultima mai mică),
 *       abandonat ({@code AbortMultipartUpload}) la orice eroare, deci obiectul nu apare pe jumătate.</li>
 *   <li><b>{@code get}</b>: {@code GetObject} cu {@code Range: bytes=a-b} (S3 range GET); un offset după sfârșit
 *       (416 la S3) dă un stream gol. Stream-ul întors abandonează conexiunea la {@code close()} dacă nu a fost citit
 *       până la capăt (nu descarcă restul doar ca să refolosească conexiunea).</li>
 *   <li><b>Upload în bucăți</b>: {@code open} = {@code CreateMultipartUpload} (referința = {@code UploadId}),
 *       {@code append(n)} = {@code UploadPart} cu {@code partNumber = n} (o retrimitere înlocuiește partea),
 *       {@code finish} = {@code CompleteMultipartUpload} cu ETag-urile în ordine (idempotent), {@code abort} =
 *       {@code AbortMultipartUpload} (idempotent) plus ștergerea obiectului, dacă upload-ul apucase să fie încheiat.
 *       </li>
 *   <li><b>Erori</b>: rețea / timeout / HTTP 5xx / 429 → {@link StorageUnavailableException}; cheie inexistentă →
 *       {@link StorageObjectNotFoundException}; sursa apelantului prea scurtă sau întreruptă și restul erorilor S3
 *       (403, bucket inexistent etc.) → {@link StorageException}. Mesajele conțin operația, codul HTTP și codul de
 *       eroare S3, niciodată credențiale.</li>
 * </ul>
 *
 * Fără stare per cerere în câmpuri: o instanță se folosește concurent.
 */
public class S3Adapter implements StorageAdapter, ChunkedWriter, AutoCloseable {

    /** Partea minimă la S3 / MinIO (cu excepția ultimei). */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;

    /** Numărul maxim de părți ale unui upload multipart (limita S3). */
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
    /** Motivul pentru care adaptorul nu e configurat (numele variabilelor lipsă), sau {@code null}. */
    private final String notConfigured;

    /**
     * @param client           clientul S3 (adaptorul îl închide la {@link #close()})
     * @param bucket           bucket-ul
     * @param partSize         mărimea părților la {@code put} multipart (minim 5 MiB)
     * @param operationTimeout timeout total pentru operațiile mici
     * @param healthTimeout    timeout total pentru {@link #health()} (maxim 2 s)
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
     * Adaptorul din configurație. Dacă lipsesc variabile ({@code S3_*}), întoarce un adaptor neconfigurat: aplicația
     * pornește, {@link #health()} e down, iar operațiile aruncă {@link StorageUnavailableException}.
     */
    public static S3Adapter fromProperties(S3Properties properties) {
        if (!properties.isConfigured()) {
            String reason = "S3 neconfigurat (lipsesc " + properties.missing() + ")";
            log.warn("{}: storage-ul S3 va fi raportat indisponibil", reason);
            return new S3Adapter(reason);
        }
        S3Adapter adapter = new S3Adapter(buildClient(properties), properties.bucket(),
                properties.partSize().toBytes(), properties.operationTimeout(), properties.healthTimeout());
        log.info("S3Adapter: endpoint {}, bucket {}, părți de {} MiB", properties.endpoint(), properties.bucket(),
                properties.partSize().toMegabytes());
        return adapter;
    }

    /** Clientul S3 pentru MinIO: path-style, Apache 5, timeout-uri scurte, fără retry. */
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
                // Offset după sfârșitul obiectului (cheia există; altfel S3 dă 404): stream gol, după contract.
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
            // Upload-ul nu mai există: dacă obiectul e deja complet, e un al doilea finish (idempotent).
            log.debug("CompleteMultipartUpload: NoSuchUpload pentru {}, verific obiectul", upload.key());
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
        // „Șterge tot ce s-a scris”: dacă finish apucase să reușească (dar sesiunea nu a fost confirmată), obiectul
        // final rămâne orfan; cheia e unică per sesiune, deci ștergerea e sigură. Idempotent.
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(upload.key()).overrideConfiguration(small()));
        } catch (SdkException e) {
            throw translate("DeleteObject", upload.key(), e, null);
        }
    }

    /** Închide clientul S3 (și pool-ul HTTP); Spring îl apelează la oprirea contextului. */
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    // --- utilitare S3 -----------------------------------------------------------------------------------------

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

    /** {@code null} dacă obiectul nu există. */
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

    /** Abandonează upload-urile multipart neterminate pentru exact această cheie; întoarce câte a găsit. */
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
            // Rămâne pentru curățarea sesiunilor (24 h, C7) sau pentru curățarea MinIO (stale_uploads_expiry, implicit
            // 24 h); eroarea inițială e cea care contează.
            log.warn("AbortMultipartUpload a eșuat pentru {}: {}", key, detail(e));
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
     * Excepția SDK → excepția din contract. {@code source} (dacă există) spune dacă eroarea vine din sursa
     * apelantului, nu din storage.
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
            // Conexiune refuzată / întreruptă, timeout (ApiCallTimeoutException e tot SdkClientException).
            return new StorageUnavailableException(TYPE, "S3 " + operation + " " + key + ": " + detail(e), e);
        }
        return new StorageException(TYPE, "S3 " + operation + " " + key + ": " + detail(e), e);
    }

    /** Motiv scurt, fără credențiale (SDK-ul nu pune cheile în mesaje; codul HTTP și codul S3 ajung). */
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

    // --- stream-uri -------------------------------------------------------------------------------------------

    /**
     * Sursa apelantului limitată la exact {@code length} octeți: nu citește mai mult (nici înainte), iar dacă sursa
     * se termină mai devreme sau aruncă, reține motivul, ca eroarea să nu fie confundată cu storage-ul căzut.
     * {@code close()} nu închide sursa (o deține apelantul).
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

        /** Următorii {@code partLength} octeți, ca stream separat (pentru o parte multipart). */
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
            // Sursa o închide apelantul.
        }
    }

    /**
     * Corpul unui {@code GetObject}: erorile SDK din timpul citirii ies ca {@link IOException}, iar {@code close()}
     * înainte de sfârșit abandonează conexiunea în loc s-o golească (important la Range-uri mici din obiecte mari și
     * la clienți HTTP care renunță).
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
