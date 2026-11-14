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
 * {@link StorageAdapter} și {@link ChunkedWriter} pe serverul FTP (vsftpd), cu commons-net (C6; planul v4 4.1, ADR-003).
 *
 * <h2>Aranjarea în {@code /srv/ftp} (rădăcina utilizatorului)</h2>
 * <pre>
 *   /o/&lt;key&gt;            obiect scris cu put (complet)
 *   /o/&lt;key&gt;~tmp        put în curs: nume temporar, redenumit atomic abia când e complet
 *   /u/&lt;key&gt;/c&lt;n&gt;       bucata n, completă
 *   /u/&lt;key&gt;/c&lt;n&gt;.part  bucata n în curs de scriere (sau rămasă parțială după o eroare)
 *   /u/&lt;key&gt;/manifest   scris la finish: prezența lui = obiectul în bucăți e complet
 * </pre>
 * Cheile sunt validate de {@link StorageKeys} (fără {@code /}, fără {@code ~}), deci niciun nume nu iese din directoarele
 * de mai sus și numele temporare nu se pot ciocni cu o cheie.
 *
 * <h2>Comportament</h2>
 * <ul>
 *   <li><b>Conexiune per operație</b> (login, mod pasiv, transfer binar), închisă la sfârșit; nu rămâne stare între
 *       apeluri, deci orice instanță poate continua un upload. Operațiile idempotente (exists, delete, finish, abort,
 *       open) reîncearcă <b>o singură dată</b> dacă o conexiune deja stabilită s-a pierdut; fără alt retry (reziliența
 *       vine la C11). Adresa din răspunsul {@code PASV} se ignoră: datele merg mereu la {@code FTP_HOST}.</li>
 *   <li><b>put / append</b>: în streaming, buffer fix de 64 KiB, sub nume temporar, apoi {@code RNFR}/{@code RNTO}; la
 *       eșec numele temporar se șterge (best-effort) și obiectul nu devine vizibil.</li>
 *   <li><b>get</b>: {@code SIZE}, apoi {@code REST} la offset și {@code RETR} în streaming; pe un obiect în bucăți
 *       concatenează bucățile în ordine pe aceeași conexiune. La sfârșitul fiecărui segment citit complet se apelează
 *       {@code completePendingCommand}; un stream închis înainte de sfârșit (sau un interval care se oprește în mijlocul
 *       fișierului) închide conexiunea, fără să aștepte restul transferului.</li>
 *   <li><b>Erori</b>: rețea, timeout, conexiune refuzată sau întreruptă și răspunsurile FTP 4xx →
 *       {@link StorageUnavailableException}; cheie inexistentă la citire → {@link StorageObjectNotFoundException};
 *       sursa apelantului prea scurtă sau întreruptă, autentificare refuzată și răspunsurile 5xx →
 *       {@link StorageException}. Mesajele conțin operația și codul FTP, niciodată parola.</li>
 * </ul>
 *
 * Fără stare per cerere în câmpuri: o instanță se folosește concurent. Apelurile pentru același upload le serializează
 * apelantul (lease per upload).
 */
public class FtpAdapter implements StorageAdapter, ChunkedWriter {

    private static final Logger log = LoggerFactory.getLogger(FtpAdapter.class);
    private static final StorageType TYPE = StorageType.FTP;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_DETAIL = 200;
    /** Cât se așteaptă răspunsul serverului când un stream e închis înainte de sfârșit. */
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
    /** Motivul pentru care adaptorul nu e configurat (numele variabilelor lipsă), sau {@code null}. */
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
     * Adaptorul din configurație. Dacă lipsesc variabile ({@code FTP_*}), întoarce un adaptor neconfigurat: aplicația
     * pornește, {@link #health()} e down, iar operațiile aruncă {@link StorageUnavailableException}.
     */
    public static FtpAdapter fromProperties(FtpProperties p) {
        if (!p.isConfigured()) {
            String reason = "FTP neconfigurat (lipsesc " + p.missing() + ")";
            log.warn("{}: storage-ul FTP va fi raportat indisponibil", reason);
            return new FtpAdapter(reason);
        }
        log.info("FtpAdapter: {}:{}, mod pasiv, conexiune per operație", p.host(), p.port());
        return new FtpAdapter(p.host().trim(), p.port(), p.user().trim(), p.password(), p.connectTimeout(),
                p.controlTimeout(), p.dataTimeout(), p.healthTimeout());
    }

    @Override
    public StorageType type() {
        return TYPE;
    }

    /** Clientul FTP nou pentru o conexiune; testele îl pot înlocui (de exemplu cu o fabrică de socket-uri). */
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
     * Creează directorul bucăților ({@code /u/<key>}) și întoarce referința {@code u/<key>}. Referința nu conține
     * secrete; writer-ul rederivă calea din cheie, deci nu depinde de ea.
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
        // Directorul bucăților există de la open(); dacă upload-ul a fost abandonat, scrierea eșuează cu 550.
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
                return upload.totalSize(); // deja încheiat (finish repetat)
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
            deleteFile(ftp, "abort", objectPath(key)); // și un obiect eventual scris cu put pe aceeași cheie
            deleteFile(ftp, "abort", objectPath(key) + "~tmp");
            return null;
        });
    }

    // --- citire -----------------------------------------------------------------------------------------------

    /** Un interval de citit dintr-un fișier: {@code length} octeți de la {@code offset}. */
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

    /** Fișier lipsă la RETR (550). */
    private static final class MissingFileException extends IOException {
        MissingFileException(String message) {
            super(message);
        }
    }

    private record Manifest(long totalSize, long chunkSize, int chunks) {
    }

    /** Manifestul unui obiect în bucăți, sau {@code null} dacă nu există (upload neterminat sau cheie necunoscută). */
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
     * Citește un obiect (sau un interval) segment cu segment, pe o singură conexiune. Un segment ajuns la capătul
     * cerut e încheiat corect cu {@code completePendingCommand} când fișierul s-a terminat acolo; dacă fișierul
     * continuă (interval care se oprește în mijloc) sau stream-ul e închis mai devreme, conexiunea se închide.
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

        /** Deschide următorul segment: {@code REST} la offset, apoi {@code RETR}. */
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

        /** Segmentul cerut a fost citit: dacă fișierul s-a terminat aici, comanda se încheie; altfel conexiunea cade. */
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
         * Încheie transferul în curs fără să citească restul datelor: închide conexiunea de date, apoi (dacă transferul
         * nu a eșuat deja) așteaptă scurt răspunsul serverului la comandă ({@code completePendingCommand}, de obicei
         * 426) și trimite {@code QUIT}, ca serverul să elibereze sesiunea imediat; la orice problemă închide brusc.
         */
        private void abandon() {
            finished = true;
            InputStream pending = data;
            data = null;
            if (pending != null) {
                try {
                    pending.close();
                } catch (IOException | RuntimeException e) {
                    // conexiunea se închide oricum mai jos
                }
                if (!failed) {
                    try {
                        ftp.setSoTimeout(GRACEFUL_CLOSE_MILLIS);
                        ftp.completePendingCommand();
                        ftp.logout();
                    } catch (IOException | RuntimeException e) {
                        // serverul nu a răspuns la timp: se închide brusc mai jos
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

    // --- scriere ----------------------------------------------------------------------------------------------

    /**
     * Scrie {@code length} octeți din {@code data} în {@code tmpPath} (STOR, în streaming) și abia apoi îl redenumește
     * în {@code finalPath} ({@code RNFR}/{@code RNTO}, atomic; înlocuiește un fișier existent). La orice eroare
     * numele temporar se șterge (best-effort), deci obiectul final nu apare pe jumătate.
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

    // --- comenzi FTP ------------------------------------------------------------------------------------------

    /** Mărimea fișierului ({@code SIZE}) sau -1 dacă nu există. */
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

    /** {@code DELE}; {@code false} dacă fișierul nu exista. */
    private boolean deleteFile(FTPClient ftp, String op, String path) throws IOException {
        if (ftp.deleteFile(path)) {
            return true;
        }
        if (ftp.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
            return false;
        }
        throw failure(op, ftp);
    }

    /** Șterge fișierele din {@code /u/<key>} și directorul; {@code false} dacă nu exista. */
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

    /** {@code MKD}; un director existent (550) nu e eroare. */
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
            // Curățare cât se poate; eroarea originală e cea care contează.
        }
    }

    // --- conexiune și erori -----------------------------------------------------------------------------------

    @FunctionalInterface
    private interface FtpOp<T> {
        T apply(FTPClient ftp) throws IOException;
    }

    /**
     * Rulează {@code body} pe o conexiune proaspătă, închisă la final. Cu {@code retryOnLostConnection}, o operație
     * idempotentă se repetă o singură dată dacă o conexiune deja stabilită s-a pierdut în timpul ei.
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
                    log.debug("FTP {}: conexiune pierdută ({}), o reîncerc o dată", op, e.getClass().getSimpleName());
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
            // Datele merg mereu la gazda configurată, nu la adresa din răspunsul PASV (care poate fi a containerului).
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
                // deja închisă
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException | RuntimeException e) {
                // conexiunea se închide oricum
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

    // --- căi --------------------------------------------------------------------------------------------------

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
     * Citește cel mult {@code length} octeți din sursa apelantului; dacă sursa se termină mai devreme sau aruncă, reține
     * motivul, ca eroarea să nu fie confundată cu storage-ul căzut. {@code close()} nu închide sursa.
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
            // Sursa o închide apelantul.
        }
    }
}
