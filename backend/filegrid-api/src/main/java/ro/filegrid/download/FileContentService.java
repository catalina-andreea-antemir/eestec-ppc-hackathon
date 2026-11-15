package ro.filegrid.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.servlet.http.HttpServletResponse;

import ro.filegrid.api.ApiException;
import ro.filegrid.ingest.DocumentEncryption;
import ro.filegrid.ingest.FileRecord;
import ro.filegrid.ingest.IngestErrors;
import ro.filegrid.integrity.Sha256;
import ro.filegrid.observability.OperationMetrics;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapterRegistry;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageObjectNotFoundException;

/**
 * File content (C8): {@code /verify} and streamed download with {@code Range}, from the storage named in the
 * {@code files} row. Nothing is held whole in memory (fixed {@value #BUFFER}-byte buffer) and the storage stream is
 * always closed, including when the client goes away.
 *
 * <p>Hashing: below {@code preVerifyMaxSize} the file is read in full and verified BEFORE sending (mismatch:
 * 409 {@code FILE_CORRUPT}, no body). Above the threshold {@code X-Content-SHA256} goes out as a header, the hash is
 * computed while sending and the last block is held back until it is verified: on a mismatch the body is never
 * completed, the connection is cut (the client sees a truncated response) and the incident is logged. A {@code Range}
 * above the threshold cannot be verified while sending (the hash covers the whole file): use {@code /verify}.
 */
@Service
@Profile("db")
public class FileContentService {

    static final int BUFFER = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(FileContentService.class);

    private final StorageAdapterRegistry registry;
    private final FileAccess access;
    private final long preVerifyMaxBytes;
    private final OperationMetrics metrics;

    @Autowired
    public FileContentService(StorageAdapterRegistry registry, FileAccess access, DownloadProperties properties,
                              OperationMetrics metrics) {
        this(registry, access, properties.preVerifyMaxSize().toBytes(), metrics);
    }

    public FileContentService(StorageAdapterRegistry registry, FileAccess access, long preVerifyMaxBytes) {
        this(registry, access, preVerifyMaxBytes, OperationMetrics.noop());
    }

    public FileContentService(StorageAdapterRegistry registry, FileAccess access, long preVerifyMaxBytes,
                              OperationMetrics metrics) {
        this.metrics = metrics;
        this.registry = registry;
        this.access = access;
        this.preVerifyMaxBytes = preVerifyMaxBytes;
    }

    /** Schema {@code VerifyResult}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifyResult(java.util.UUID fileId, boolean ok, String algorithm, boolean deep,
                               String expectedSha256, String actualSha256, String expectedSha256Plain,
                               String actualSha256Plain, Instant verifiedAt) {
    }

    /**
     * {@code GET /files/{id}/verify}: recomputes SHA-256 from storage, streamed. {@code deep} (encrypted documents only)
     * also decrypts while streaming and checks {@code sha256Plain}; otherwise the stored bytes are verified.
     */
    public VerifyResult verify(FileRecord file, boolean deep) {
        return metrics.time(file.category(), file.storage(), OperationMetrics.VERIFY, file.size(),
                () -> doVerify(file, deep));
    }

    private VerifyResult doVerify(FileRecord file, boolean deep) {
        byte[] actual = hashStored(file);
        if (!Arrays.equals(actual, file.sha256())) {
            throw corrupt(file, actual);
        }
        if (deep && file.encrypted() && file.sha256Plain() != null) {
            byte[] plain = hashDecrypted(file);
            if (!Arrays.equals(plain, file.sha256Plain())) {
                throw corrupt(file, actual);
            }
            return new VerifyResult(file.id(), true, "SHA-256", true, Sha256.hex(file.sha256()), Sha256.hex(actual),
                    Sha256.hex(file.sha256Plain()), Sha256.hex(plain), Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
        return new VerifyResult(file.id(), true, "SHA-256", false, Sha256.hex(file.sha256()), Sha256.hex(actual),
                null, null, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** Sends the file (or the requested range) on {@code response}; errors before the first byte surface as {@link ApiException}. */
    public void send(FileRecord file, String rangeHeader, HttpServletResponse response) throws IOException {
        try {
            metrics.run(file.category(), file.storage(), OperationMetrics.DOWNLOAD, file.size(), () -> {
                try {
                    doSend(file, rangeHeader, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void doSend(FileRecord file, String rangeHeader, HttpServletResponse response) throws IOException {
        boolean encrypted = file.encrypted(); // CONNECTION_DOC: no Range (encrypted in segments), full 200
        Optional<RangeHeader.Resolved> range = encrypted ? Optional.empty() : RangeHeader.parse(rangeHeader, file.size());
        boolean small = file.size() <= preVerifyMaxBytes;
        if (small) {
            byte[] actual = hashStored(file);
            if (!Arrays.equals(actual, file.sha256())) {
                throw corrupt(file, actual);
            }
        }
        long length = range.map(RangeHeader.Resolved::length).orElse(file.size());
        boolean verifyWhileSending = !small && range.isEmpty() && !encrypted;

        InputStream stored = open(file, range.map(RangeHeader.Resolved::byteRange).orElse(ByteRange.full()));
        try (InputStream in = encrypted ? access.decrypt(file, stored) : stored) {
            byte[] buffer = new byte[BUFFER];
            MessageDigest digest = verifyWhileSending ? Sha256.newDigest() : null;
            // Read the first block before writing headers, so a storage error can still become a clean 503.
            int n = read(file, in, buffer, length, 0);
            if (digest != null) {
                digest.update(buffer, 0, n);
                if (n == length) { // single-block file: a 409 is still possible
                    byte[] actual = digest.digest();
                    if (!Arrays.equals(actual, file.sha256()) || in.read() != -1) {
                        throw corrupt(file, actual);
                    }
                }
            }
            headers(file, range, length, response);
            OutputStream out = response.getOutputStream();
            long sent = 0;
            while (true) {
                if (digest != null && sent > 0) {
                    digest.update(buffer, 0, n);
                }
                if (digest != null && sent + n == length && sent > 0) {
                    checkWhileSending(file, digest, in);
                }
                try {
                    out.write(buffer, 0, n);
                } catch (IOException clientGone) {
                    log.debug("Client abandoned download {} after {} bytes", file.id(), sent);
                    return;
                }
                sent += n;
                if (sent >= length) {
                    break;
                }
                n = read(file, in, buffer, length, sent);
            }
            try {
                out.flush();
            } catch (IOException clientGone) {
                log.debug("Client abandoned download {} at the end", file.id());
            }
        }
    }

    /** The last block is still held back: if the hash does not match, the body is never completed. */
    private void checkWhileSending(FileRecord file, MessageDigest digest, InputStream in) {
        byte[] actual = digest.digest();
        boolean extra;
        try {
            extra = in.read() != -1;
        } catch (IOException e) {
            extra = false;
        }
        if (extra || !Arrays.equals(actual, file.sha256())) {
            metrics.integrityFailure(file.category(), file.storage());
            log.error("Integrity violation during download: fileId={}, storage={}, expectedSha256={}, actualSha256={};"
                    + " cutting the connection", file.id(), file.storage(), Sha256.hex(file.sha256()),
                    Sha256.hex(actual));
            throw new TransferAborted("Fișier corupt: " + file.id());
        }
    }

    private int read(FileRecord file, InputStream in, byte[] buffer, long length, long sent) {
        try {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - sent));
            if (n < 0) {
                throw truncated(file, sent, length, null);
            }
            return n;
        } catch (DocumentEncryption.CorruptCiphertextException e) {
            metrics.integrityFailure(file.category(), file.storage());
            log.error("Tampered encrypted content: fileId={}, storage={}, sent {} bytes: {}", file.id(), file.storage(),
                    sent, e.getMessage());
            if (sent == 0) {
                throw corruptContent(file);
            }
            throw new TransferAborted("Fișier corupt: " + file.id());
        } catch (IOException e) {
            throw truncated(file, sent, length, e);
        }
    }

    private RuntimeException truncated(FileRecord file, long sent, long length, IOException cause) {
        log.error("Storage read interrupted: fileId={}, storage={}, sent {} of {} bytes", file.id(),
                file.storage(), sent, length, cause);
        if (sent == 0) {
            return IngestErrors.storageUnavailable("Storage-ul " + file.storage() + " nu poate citi fișierul acum.");
        }
        return new TransferAborted("Citire întreruptă: " + file.id());
    }

    private void headers(FileRecord file, Optional<RangeHeader.Resolved> range, long length,
                         HttpServletResponse response) {
        response.setStatus(range.isPresent() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(file.contentType() == null || file.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.contentType());
        response.setHeader("X-Content-SHA256", Sha256.hex(file.encrypted() && file.sha256Plain() != null
                ? file.sha256Plain() : file.sha256()));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition(file.originalName()));
        if (!file.encrypted()) {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setContentLengthLong(length);
        }
        range.ifPresent(r -> response.setHeader(HttpHeaders.CONTENT_RANGE, r.contentRange()));
    }

    static String disposition(String name) {
        String clean = name == null || name.isBlank() ? "fisier" : name;
        String ascii = clean.replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_");
        String header = "attachment; filename=\"" + ascii + "\"";
        return ascii.equals(clean) ? header
                : header + "; filename*=UTF-8''" + UriUtils.encode(clean, StandardCharsets.UTF_8);
    }

    private InputStream open(FileRecord file, ByteRange range) {
        try {
            return registry.adapter(file.storage()).get(file.storageKey(), range);
        } catch (StorageObjectNotFoundException e) {
            log.error("Object missing from storage: fileId={}, storage={}", file.id(), file.storage());
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conținutul fișierului nu mai există în storage.");
        } catch (StorageException e) {
            log.warn("Storage {} could not open file {}: {}", file.storage(), file.id(), e.getMessage());
            throw IngestErrors.storageUnavailable("Storage-ul " + file.storage() + " nu este disponibil acum.");
        }
    }

    private byte[] hashStored(FileRecord file) {
        MessageDigest digest = Sha256.newDigest();
        try (InputStream in = open(file, ByteRange.full())) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException e) {
            log.warn("Reading file {} from {} failed: {}", file.id(), file.storage(), e.getMessage());
            throw IngestErrors.storageUnavailable("Storage-ul " + file.storage() + " nu poate citi fișierul acum.");
        }
        return digest.digest();
    }

    /** Decrypts from storage while streaming and returns SHA-256 of the plaintext; a tampered segment -> 409. */
    private byte[] hashDecrypted(FileRecord file) {
        MessageDigest digest = Sha256.newDigest();
        try (InputStream in = access.decrypt(file, open(file, ByteRange.full()))) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        } catch (DocumentEncryption.CorruptCiphertextException e) {
            throw corruptContent(file);
        } catch (IOException e) {
            log.warn("Reading file {} from {} failed: {}", file.id(), file.storage(), e.getMessage());
            throw IngestErrors.storageUnavailable("Storage-ul " + file.storage() + " nu poate citi fișierul acum.");
        }
        return digest.digest();
    }

    private ApiException corruptContent(FileRecord file) {
        metrics.integrityFailure(file.category(), file.storage());
        log.error("Tampered encrypted content: fileId={}, storage={}", file.id(), file.storage());
        return new ApiException(HttpStatus.CONFLICT, "FILE_CORRUPT", "fișier corupt", null,
                Map.of("fileId", file.id().toString(), "expectedSha256", Sha256.hex(file.sha256())));
    }

    private ApiException corrupt(FileRecord file, byte[] actual) {
        metrics.integrityFailure(file.category(), file.storage());
        log.error("Corrupt file: fileId={}, storage={}, expectedSha256={}, actualSha256={}", file.id(), file.storage(),
                Sha256.hex(file.sha256()), Sha256.hex(actual));
        return new ApiException(HttpStatus.CONFLICT, "FILE_CORRUPT", "fișier corupt", null,
                Map.of("fileId", file.id().toString(), "expectedSha256", Sha256.hex(file.sha256()),
                        "actualSha256", Sha256.hex(actual)));
    }

    /** The transfer broke after the response started: no JSON can be sent anymore, so the connection is cut. */
    static final class TransferAborted extends RuntimeException {
        TransferAborted(String message) {
            super(message);
        }
    }
}
