package ro.filegrid.storage.ftp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPCmd;
import org.apache.commons.net.ftp.FTPReply;
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

/**
 * {@link StorageAdapter} and {@link ChunkedWriter} on the FTP server (vsftpd), using commons-net (C6, ADR-003).
 *
 * <h2>Layout under {@code /srv/ftp} (the user's root)</h2>
 * <pre>
 *   /o/&lt;key&gt;            object written with put (complete)
 *   /o/&lt;key&gt;~tmp        put in progress: temp name, atomically renamed only once complete
 *   /u/&lt;key&gt;/c&lt;n&gt;       chunk n, complete
 *   /u/&lt;key&gt;/c&lt;n&gt;.part  chunk n being written (or left partial after an error)
 *   /u/&lt;key&gt;/manifest   written on finish: its presence = the chunked object is complete
 * </pre>
 * Keys are validated by {@link StorageKeys} (no {@code /}, no {@code ~}), so no name escapes the directories above
 * and temp names can never collide with a key.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>One connection per operation</b> (login, passive mode, binary transfer), closed at the end; no state is
 *       kept between calls, so any instance can continue an upload. Idempotent operations (exists, delete, finish,
 *       abort, open) retry <b>once</b> if an already established connection was lost; no other retry (resilience
 *       is layered on top, C11). The address in the {@code PASV} reply is ignored: data always goes to
 *       {@code FTP_HOST}.</li>
 *   <li><b>put / append</b>: streamed with a fixed 64 KiB buffer under a temp name, then {@code RNFR}/{@code RNTO};
 *       on failure the temp name is deleted (best effort) and the object never becomes visible.</li>
 *   <li><b>get</b>: {@code SIZE}, then {@code REST} at the offset and a streamed {@code RETR}; for a chunked object
 *       the chunks are concatenated in order on the same connection. {@code completePendingCommand} is called at the
 *       end of each fully read segment; a stream closed early (or a range stopping mid-file) closes the connection
 *       without waiting for the rest of the transfer.</li>
 *   <li><b>Errors</b>: network, timeout, refused or dropped connection and FTP 4xx replies ->
 *       {@link StorageUnavailableException}; missing key on read -> {@link StorageObjectNotFoundException};
 *       a short or broken caller source, rejected login and 5xx replies -> {@link StorageException}. Messages include
 *       the operation and FTP code, never the password.</li>
 * </ul>
 *
 * No per-request state in fields: one instance is used concurrently. Calls for the same upload are serialized by
 * the caller (per-upload lease).
 */
public class FtpAdapter implements StorageAdapter, ChunkedWriter {

    private static final Logger log = LoggerFactory.getLogger(FtpAdapter.class);
    private static final StorageType TYPE = StorageType.FTP;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_DETAIL = 200;
    /** How long to wait for the server's reply when a stream is closed early. */
    private static final int GRACEFUL_CLOSE_MILLIS = 2000;
    private static final int MAX_MANIFEST_BYTES = 4096;
    private static final String MANIFEST_HEADER = "filegrid-ftp-manifest v1";

    private static final String OBJECTS = "/o";
    private static final String UPLOADS = "/u";

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final Duration connectTimeout;
    private final Duration controlTimeout;
    private final Duration dataTimeout;
    private final Duration healthTimeout;
    /** Why the adapter is not configured (names of the missing variables), or {@code null}. */
    private final String notConfigured;

    public FtpAdapter(String host, int port, String user, String password, Duration connectTimeout,
            Duration controlTimeout, Duration dataTimeout, Duration healthTimeout) {
        this.host = requireText(host, "host");
        this.port = port;
        this.user = requireText(user, "user");
        this.password = requireText(password, "password");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.controlTimeout = Objects.requireNonNull(controlTimeout, "controlTimeout");
        this.dataTimeout = Objects.requireNonNull(dataTimeout, "dataTimeout");
        this.healthTimeout = Objects.requireNonNull(healthTimeout, "healthTimeout");
        this.notConfigured = null;
    }

    private FtpAdapter(String notConfigured) {
        this.host = null;
        this.port = 0;
        this.user = null;
        this.password = null;
        this.connectTimeout = Duration.ZERO;
        this.controlTimeout = Duration.ZERO;
        this.dataTimeout = Duration.ZERO;
        this.healthTimeout = Duration.ZERO;
        this.notConfigured = notConfigured;
    }

    /**
     * Adapter built from configuration. If variables ({@code FTP_*}) are missing, returns an unconfigured adapter: the
     * app starts, {@link #health()} is down and operations throw {@link StorageUnavailableException}.
     */
    public static FtpAdapter fromProperties(FtpProperties p) {
        if (!p.isConfigured()) {
            String reason = "FTP neconfigurat (lipsesc " + p.missing() + ")";
            log.warn("{}: FTP storage will be reported as unavailable", reason);
            return new FtpAdapter(reason);
        }
        log.info("FtpAdapter: {}:{}, passive mode, one connection per operation", p.host(), p.port());
        return new FtpAdapter(p.host().trim(), p.port(), p.user().trim(), p.password(), p.connectTimeout(),
                p.controlTimeout(), p.dataTimeout(), p.healthTimeout());
    }

    @Override
    public StorageType type() {
        return TYPE;
    }

    /** A fresh FTP client for one connection; tests can override it (e.g. with a socket factory). */
    protected FTPClient newClient() {
        return new FTPClient();
    }

    // --- StorageAdapter ---------------------------------------------------------------------------------------

    @Override
    public long put(String key, InputStream data, long length, String contentType) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(data, "data");
        if (length <= 0) {
            throw new IllegalArgumentException("length trebuie să fie pozitiv");
        }
        String finalPath = objectPath(key);
        String tmpPath = finalPath + "~tmp";
        run("put", false, ftp -> {
            ensureDir(ftp, OBJECTS);
            writeAtomically(ftp, "put", tmpPath, finalPath, data, length);
            return null;
        });
        return length;
    }

    @Override
    public InputStream get(String key, ByteRange range) {
        StorageKeys.requireValid(key);
        Objects.requireNonNull(range, "range");
        requireConfigured();
        FTPClient ftp = null;
        try {
            ftp = connect(connectTimeout, controlTimeout);
            List<Segment> segments = planRead(ftp, key, range);
            if (segments.isEmpty()) {
                disconnectQuietly(ftp);
                return new ByteArrayInputStream(new byte[0]);
            }
            ObjectStream stream = new ObjectStream(this, ftp, segments.iterator());
            stream.openNext();
            return stream;
        } catch (MissingFileException e) {
            disconnectQuietly(ftp);
            throw new StorageObjectNotFoundException(TYPE, key);
        } catch (IOException e) {
            disconnectQuietly(ftp);
            throw translate("get", e);
        } catch (RuntimeException e) {
            disconnectQuietly(ftp);
            throw e;
        }
    }

    @Override
    public boolean delete(String key) {
        StorageKeys.requireValid(key);
        return run("delete", true, ftp -> {
            boolean deleted = deleteFile(ftp, "delete", objectPath(key));
            deleted |= deleteFile(ftp, "delete", objectPath(key) + "~tmp");
            deleted |= deleteUploadDir(ftp, "delete", key);
            return deleted;
        });
    }

    @Override
    public boolean exists(String key) {
        StorageKeys.requireValid(key);
        return run("exists", true, ftp -> sizeOf(ftp, "exists", objectPath(key)) >= 0
                || sizeOf(ftp, "exists", manifestPath(key)) >= 0);
    }

    @Override
    public StorageHealth health() {
        long start = System.nanoTime();
        if (notConfigured != null) {
            return StorageHealth.down(TYPE, Duration.ZERO, notConfigured);
        }
        FTPClient ftp = null;
        try {
            long deadline = start + healthTimeout.toNanos();
            ftp = connect(remaining(deadline), remaining(deadline), deadline);
            ftp.setSoTimeout(millis(remaining(deadline)));
            if (!ftp.sendNoOp()) {
                return StorageHealth.down(TYPE, elapsed(start), "NOOP a eșuat (cod " + ftp.getReplyCode() + ")");
            }
            return StorageHealth.up(TYPE, elapsed(start));
        } catch (IOException e) {
            return StorageHealth.down(TYPE, elapsed(start), detail(e));
        } catch (RuntimeException e) {
            return StorageHealth.down(TYPE, elapsed(start), detail(e));
        } finally {
            disconnectQuietly(ftp);
        }
    }

    // --- ChunkedWriter ----------------------------------------------------------------------------------------

    /**
     * Creates the chunk directory ({@code /u/<key>}) and returns the reference {@code u/<key>}. The reference holds no
     * secrets; the writer re-derives the path from the key, so it does not depend on it.
     */
    @Override
    public String open(String key, long totalSize, int chunkSize) {
        StorageKeys.requireValid(key);
        if (totalSize <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException("totalSize și chunkSize trebuie să fie pozitive");
        }
        run("open", true, ftp -> {
            ensureDir(ftp, UPLOADS);
            ensureDir(ftp, uploadDir(key));
            return null;
        });
        return "u/" + key;
    }

    @Override
    public ChunkReceipt append(ChunkedUpload upload, int n, InputStream data, long length) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(data, "data");
        upload.requireChunkNumber(n);
        if (length != upload.expectedLength(n)) {
            throw new IllegalArgumentException(
                    "Bucata " + n + " are " + upload.expectedLength(n) + " octeți, nu " + length);
        }
        String finalPath = chunkPath(upload.key(), n);
        // The chunk directory exists since open(); if the upload was abandoned, the write fails with 550.
        run("append", false, ftp -> {
            writeAtomically(ftp, "append", finalPath + ".part", finalPath, data, length);
            return null;
        });
        return new ChunkReceipt(n, length, null);
    }

    @Override
    public long finish(ChunkedUpload upload, List<ChunkReceipt> chunks) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(chunks, "chunks");
        String key = upload.key();
        return run("finish", true, ftp -> {
            if (sizeOf(ftp, "finish", manifestPath(key)) >= 0) {
                return upload.totalSize(); // already completed (repeated finish)
            }
            int total = upload.totalChunks();
            if (chunks.size() != total) {
                throw new StorageException(TYPE, "FTP finish: sunt " + chunks.size() + " bucăți confirmate, "
                        + "se așteptau " + total);
            }
            for (int n = 1; n <= total; n++) {
                ChunkReceipt receipt = chunks.get(n - 1);
                if (receipt.n() != n || receipt.size() != upload.expectedLength(n)) {
                    throw new StorageException(TYPE, "FTP finish: confirmarea bucății " + n + " nu se potrivește");
                }
                long actual = sizeOf(ftp, "finish", chunkPath(key, n));
                if (actual != receipt.size()) {
                    throw new StorageException(TYPE, "FTP finish: bucata " + n + " lipsește sau are alt număr de "
                            + "octeți pe server");
                }
            }
            byte[] manifest = (MANIFEST_HEADER + "\ntotalSize=" + upload.totalSize() + "\nchunkSize="
                    + upload.chunkSize() + "\nchunks=" + total + "\n").getBytes(StandardCharsets.US_ASCII);
            String finalPath = manifestPath(key);
            writeAtomically(ftp, "finish", finalPath + ".part", finalPath, new ByteArrayInputStream(manifest),
                    manifest.length);
            return upload.totalSize();
        });
    }

    @Override
    public void abort(ChunkedUpload upload) {
        Objects.requireNonNull(upload, "upload");
        String key = upload.key();
        run("abort", true, ftp -> {
            deleteUploadDir(ftp, "abort", key);
            deleteFile(ftp, "abort", objectPath(key)); // and any object written with put under the same key
            deleteFile(ftp, "abort", objectPath(key) + "~tmp");
            return null;
        });
    }

    // --- reading ----------------------------------------------------------------------------------------------

    /** A range to read from one file: {@code length} bytes from {@code offset}. */
    private record Segment(String path, long offset, long length) {
    }

    private List<Segment> planRead(FTPClient ftp, String key, ByteRange range) throws IOException {
        long size = sizeOf(ftp, "get", objectPath(key));
        if (size >= 0) {
            long length = range.lengthWithin(size);
            return length == 0 ? List.of() : List.of(new Segment(objectPath(key), range.offset(), length));
        }
        Manifest manifest = readManifest(ftp, key);
        if (manifest == null) {
            throw new StorageObjectNotFoundException(TYPE, key);
        }
        long length = range.lengthWithin(manifest.totalSize);
        if (length == 0) {
            return List.of();
        }
        long start = range.offset();
        long end = start + length;
        List<Segment> segments = new ArrayList<>();
        long chunkSize = manifest.chunkSize;
        for (long chunk = start / chunkSize; chunk * chunkSize < end; chunk++) {
            long chunkStart = chunk * chunkSize;
            long from = Math.max(start, chunkStart) - chunkStart;
            long to = Math.min(end, chunkStart + chunkSize) - chunkStart;
            segments.add(new Segment(chunkPath(key, Math.toIntExact(chunk + 1)), from, to - from));
        }
        return segments;
    }

    /** File missing on RETR (550). */
    private static final class MissingFileException extends IOException {
        MissingFileException(String message) {
            super(message);
        }
    }

    private record Manifest(long totalSize, long chunkSize, int chunks) {
    }

    /** Manifest of a chunked object, or {@code null} if absent (unfinished upload or unknown key). */
    private Manifest readManifest(FTPClient ftp, String key) throws IOException {
        String path = manifestPath(key);
        long size = sizeOf(ftp, "get", path);
        if (size < 0) {
            return null;
        }
        if (size > MAX_MANIFEST_BYTES) {
            throw new StorageException(TYPE, "FTP get: manifest invalid (prea mare)");
        }
        byte[] bytes;
        try (InputStream in = ftp.retrieveFileStream(path)) {
            if (in == null) {
                throw failure("get", ftp);
            }
            bytes = in.readNBytes(MAX_MANIFEST_BYTES);
        }
        if (!ftp.completePendingCommand()) {
            throw failure("get", ftp);
        }
        try {
            String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n");
            if (lines.length != 4 || !MANIFEST_HEADER.equals(lines[0])) {
                throw new IllegalArgumentException("antet");
            }
            long totalSize = Long.parseLong(value(lines[1], "totalSize="));
            long chunkSize = Long.parseLong(value(lines[2], "chunkSize="));
            int chunks = Integer.parseInt(value(lines[3], "chunks="));
            if (totalSize <= 0 || chunkSize <= 0 || chunks != (totalSize + chunkSize - 1) / chunkSize) {
                throw new IllegalArgumentException("valori");
            }
            return new Manifest(totalSize, chunkSize, chunks);
        } catch (IllegalArgumentException e) {
            throw new StorageException(TYPE, "FTP get: manifest invalid pentru obiectul " + key);
        }
    }

    private static String value(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new IllegalArgumentException(prefix);
        }
        return line.substring(prefix.length());
    }

    /**
     * Reads an object (or a range) segment by segment over a single connection. A segment that reaches the requested end
     * is closed cleanly with {@code completePendingCommand} when the file ends there; if the file continues (range
     * stopping midway) or the stream is closed early, the connection is closed.
     */
    private static final class ObjectStream extends InputStream {

        private final FtpAdapter adapter;
        private final FTPClient ftp;
        private final Iterator<Segment> segments;
        private InputStream data;
        private long left;
        private boolean closed;
        private boolean finished;
        private boolean failed;

        ObjectStream(FtpAdapter adapter, FTPClient ftp, Iterator<Segment> segments) {
            this.adapter = adapter;
            this.ftp = ftp;
            this.segments = segments;
        }

        /** Opens the next segment: {@code REST} at the offset, then {@code RETR}. */
        void openNext() throws IOException {
            Segment segment = segments.next();
            ftp.setRestartOffset(segment.offset());
            InputStream in = ftp.retrieveFileStream(segment.path());
            if (in == null) {
                int code = ftp.getReplyCode();
                if (code == FTPReply.FILE_UNAVAILABLE) {
                    throw new MissingFileException("FTP: fișierul " + segment.path() + " lipsește de pe server");
                }
                throw new IOException("FTP: RETR a eșuat (cod " + code + ")");
            }
            data = in;
            left = segment.length();
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
            if (closed) {
                throw new IOException("stream închis");
            }
            if (len == 0) {
                return 0;
            }
            if (failed) {
                throw new IOException("FTP: transferul a eșuat");
            }
            if (finished) {
                return -1;
            }
            try {
                int r = data.read(buf, off, (int) Math.min(len, left));
                if (r < 0) {
                    throw new IOException("FTP: fișierul de pe server e mai scurt decât se aștepta");
                }
                left -= r;
                if (left == 0) {
                    endSegment();
                }
                return r;
            } catch (IOException e) {
                failed = true;
                abandon();
                throw e;
            }
        }

        @Override
        public int available() {
            return 0;
        }

        /** The requested segment has been read: if the file ends here the command is completed; otherwise the connection is dropped. */
        private void endSegment() throws IOException {
            boolean naturalEnd = data.read() < 0;
            if (!naturalEnd) {
                if (segments.hasNext()) {
                    throw new IOException("FTP: bucata e mai lungă decât indică manifestul");
                }
                abandon();
                return;
            }
            data.close();
            data = null;
            if (!ftp.completePendingCommand()) {
                throw new IOException("FTP: transferul nu s-a încheiat cu succes (cod " + ftp.getReplyCode() + ")");
            }
            if (segments.hasNext()) {
                openNext();
            } else {
                finished = true;
                adapter.disconnectQuietly(ftp);
            }
        }

        /**
         * Ends the current transfer without reading the remaining data: closes the data connection, then (unless the
         * transfer already failed) briefly waits for the server's reply ({@code completePendingCommand}, usually 426) and
         * sends {@code QUIT} so the server frees the session right away; on any problem it closes abruptly.
         */
        private void abandon() {
            finished = true;
            InputStream pending = data;
            data = null;
            if (pending != null) {
                try {
                    pending.close();
                } catch (IOException | RuntimeException e) {
                    // the connection is closed below anyway
                }
                if (!failed) {
                    try {
                        ftp.setSoTimeout(GRACEFUL_CLOSE_MILLIS);
                        ftp.completePendingCommand();
                        ftp.logout();
                    } catch (IOException | RuntimeException e) {
                        // the server did not reply in time: closed abruptly below
                    }
                }
            }
            adapter.disconnectQuietly(ftp);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            abandon();
        }
    }

    // --- writing ----------------------------------------------------------------------------------------------

    /**
     * Writes {@code length} bytes from {@code data} to {@code tmpPath} (streamed STOR) and only then renames it to
     * {@code finalPath} ({@code RNFR}/{@code RNTO}, atomic; replaces an existing file). On any error the temp name is
     * deleted (best effort), so the final object never shows up half-written.
     */
    private void writeAtomically(FTPClient ftp, String op, String tmpPath, String finalPath, InputStream data,
            long length) throws IOException {
        BoundedSource source = new BoundedSource(data, length);
        OutputStream out = null;
        boolean ok = false;
        try {
            out = ftp.storeFileStream(tmpPath);
            if (out == null) {
                throw failure(op, ftp);
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            long left = length;
            while (left > 0) {
                int r;
                try {
                    r = source.read(buffer, 0, (int) Math.min(buffer.length, left));
                } catch (IOException e) {
                    throw new StorageException(TYPE, "FTP " + op + ": " + source.failure());
                }
                out.write(buffer, 0, r);
                left -= r;
            }
            out.close();
            out = null;
            if (!ftp.completePendingCommand()) {
                throw failure(op, ftp);
            }
            if (!ftp.rename(tmpPath, finalPath)) {
                throw failure(op, ftp);
            }
            ok = true;
        } finally {
            if (!ok) {
                closeQuietly(out);
                disconnectQuietly(ftp);
                deleteQuietly(tmpPath);
            }
        }
    }

    // --- FTP commands -----------------------------------------------------------------------------------------

    /** File size ({@code SIZE}) or -1 if it does not exist. */
    private long sizeOf(FTPClient ftp, String op, String path) throws IOException {
        int code = ftp.sendCommand(FTPCmd.SIZE, path);
        if (code == FTPReply.FILE_STATUS) {
            String reply = ftp.getReplyString().trim();
            try {
                return Long.parseLong(reply.substring(reply.indexOf(' ') + 1).trim());
            } catch (NumberFormatException e) {
                throw new StorageException(TYPE, "FTP " + op + ": răspuns SIZE necunoscut");
            }
        }
        if (code == FTPReply.FILE_UNAVAILABLE) {
            return -1;
        }
        throw failure(op, ftp);
    }

    /** {@code DELE}; {@code false} if the file did not exist. */
    private boolean deleteFile(FTPClient ftp, String op, String path) throws IOException {
        if (ftp.deleteFile(path)) {
            return true;
        }
        if (ftp.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
            return false;
        }
        throw failure(op, ftp);
    }

    /** Deletes the files in {@code /u/<key>} and the directory; {@code false} if it did not exist. */
    private boolean deleteUploadDir(FTPClient ftp, String op, String key) throws IOException {
        String dir = uploadDir(key);
        if (!ftp.changeWorkingDirectory(dir)) {
            if (ftp.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
                return false;
            }
            throw failure(op, ftp);
        }
        String[] names = ftp.listNames();
        if (names != null) {
            for (String name : names) {
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!base.isEmpty() && !base.equals(".") && !base.equals("..")) {
                    deleteFile(ftp, op, dir + "/" + base);
                }
            }
        }
        ftp.changeWorkingDirectory("/");
        if (!ftp.removeDirectory(dir) && ftp.getReplyCode() != FTPReply.FILE_UNAVAILABLE) {
            throw failure(op, ftp);
        }
        return true;
    }

    /** {@code MKD}; an existing directory (550) is not an error. */
    private void ensureDir(FTPClient ftp, String dir) throws IOException {
        if (!ftp.makeDirectory(dir) && ftp.getReplyCode() != FTPReply.FILE_UNAVAILABLE) {
            throw failure("mkdir", ftp);
        }
    }

    private void deleteQuietly(String path) {
        try {
            if (notConfigured == null) {
                run("cleanup", false, ftp -> deleteFile(ftp, "cleanup", path));
            }
        } catch (RuntimeException e) {
            // Best-effort cleanup; the original error is the one that matters.
        }
    }

    // --- connection and errors --------------------------------------------------------------------------------

    @FunctionalInterface
    private interface FtpOp<T> {
        T apply(FTPClient ftp) throws IOException;
    }

    /**
     * Runs {@code body} on a fresh connection, closed at the end. With {@code retryOnLostConnection}, an idempotent
     * operation is repeated once if an already established connection was lost during it.
     */
    private <T> T run(String op, boolean retryOnLostConnection, FtpOp<T> body) {
        requireConfigured();
        for (int attempt = 1;; attempt++) {
            FTPClient ftp = null;
            boolean connected = false;
            try {
                ftp = connect(connectTimeout, controlTimeout);
                connected = true;
                return body.apply(ftp);
            } catch (IOException e) {
                if (retryOnLostConnection && connected && attempt == 1 && !(e instanceof ConnectException)) {
                    log.debug("FTP {}: connection lost ({}), retrying once", op, e.getClass().getSimpleName());
                    continue;
                }
                throw translate(op, e);
            } finally {
                disconnectQuietly(ftp);
            }
        }
    }

    private FTPClient connect(Duration connectTimeout, Duration controlTimeout) throws IOException {
        return connect(connectTimeout, controlTimeout, Long.MAX_VALUE);
    }

    private FTPClient connect(Duration connectT, Duration controlT, long deadline) throws IOException {
        FTPClient ftp = newClient();
        try {
            ftp.setConnectTimeout(millis(connectT));
            ftp.setDefaultTimeout(millis(controlT));
            ftp.setDataTimeout(dataTimeout);
            ftp.setBufferSize(BUFFER_SIZE);
            // Data always goes to the configured host, not the PASV reply address (which may be the container's own IP).
            ftp.setPassiveNatWorkaroundStrategy(hostname -> host);
            ftp.connect(host, port);
            ftp.setSoTimeout(millis(deadline == Long.MAX_VALUE ? controlT : remaining(deadline)));
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new StorageUnavailableException(TYPE,
                        "FTP: serverul a refuzat conexiunea (cod " + ftp.getReplyCode() + ")");
            }
            if (!ftp.login(user, password)) {
                throw new StorageException(TYPE, "FTP: autentificare refuzată (cod " + ftp.getReplyCode() + ")");
            }
            ftp.enterLocalPassiveMode();
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw failure("connect", ftp);
            }
            return ftp;
        } catch (IOException | RuntimeException e) {
            disconnectQuietly(ftp);
            throw e;
        }
    }

    private StorageException failure(String op, FTPClient ftp) {
        int code = ftp.getReplyCode();
        String message = "FTP " + op + " a eșuat (cod " + code + ")";
        return code >= 400 && code < 500
                ? new StorageUnavailableException(TYPE, message)
                : new StorageException(TYPE, message);
    }

    private StorageException translate(String op, IOException e) {
        return new StorageUnavailableException(TYPE, "FTP " + op + ": " + detail(e), e);
    }

    private static String detail(Exception e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) : text;
    }

    private void requireConfigured() {
        if (notConfigured != null) {
            throw new StorageUnavailableException(TYPE, notConfigured);
        }
    }

    private void disconnectQuietly(FTPClient ftp) {
        if (ftp != null) {
            try {
                if (ftp.isConnected()) {
                    ftp.disconnect();
                }
            } catch (IOException | RuntimeException e) {
                // already closed
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException | RuntimeException e) {
                // the connection is closed anyway
            }
        }
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return Duration.ofNanos(Math.max(left, Duration.ofMillis(50).toNanos()));
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static int millis(Duration d) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, d.toMillis()));
    }

    // --- paths ------------------------------------------------------------------------------------------------

    private static String objectPath(String key) {
        return OBJECTS + "/" + key;
    }

    private static String uploadDir(String key) {
        return UPLOADS + "/" + key;
    }

    private static String chunkPath(String key, int n) {
        return uploadDir(key) + "/c" + n;
    }

    private static String manifestPath(String key) {
        return uploadDir(key) + "/manifest";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " lipsește");
        }
        return value;
    }

    /**
     * Reads at most {@code length} bytes from the caller's source; if the source ends early or throws, the reason is
     * recorded so the error is not mistaken for a storage outage. {@code close()} does not close the source.
     */
    static final class BoundedSource extends InputStream {

        private final InputStream in;
        private final long length;
        private long read;
        private String failure = "sursa a eșuat";

        BoundedSource(InputStream in, long length) {
            this.in = in;
            this.length = length;
        }

        String failure() {
            return failure;
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
}
