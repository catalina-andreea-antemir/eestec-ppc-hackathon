package ro.filegrid.storage.ftp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.Assumptions;

import ro.filegrid.storage.ChunkedUpload;
import ro.filegrid.storage.StorageHealth;

/**
 * Glue between the integration tests and the stack's real FTP server. Start: {@code ./scripts/start.sh}, then the
 * development ports ({@code docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d}: FTP on
 * {@code 127.0.0.1:21}, passive ports 21000-21049, with {@code FTP_PASV_ADDRESS=127.0.0.1}).
 *
 * <p>Credentials belong to the app user ({@code FTP_USER} / {@code FTP_PASSWORD}), from environment variables or the
 * repo-root {@code .env} (never printed). Server: {@code FTP_TEST_HOST} (default {@code 127.0.0.1}) and
 * {@code FTP_TEST_PORT} (default 21). If credentials are missing or the server does not answer, the tests are
 * <b>skipped</b> (assumption) instead of failing: {@code ./mvnw test} works without the stack.
 */
final class FtpTestSupport {

    static final String DEFAULT_HOST = "127.0.0.1";

    private FtpTestSupport() {
    }

    /** Adapter connected to the real server, or skips the class's tests if FTP is not available. */
    static TrackingFtpAdapter connectOrSkip() {
        FtpProperties props = properties();
        Assumptions.assumeTrue(props != null,
                "FTP: lipsesc credentialele FTP_USER / FTP_PASSWORD (mediu sau .env); test sarit");
        TrackingFtpAdapter adapter = new TrackingFtpAdapter(props);
        StorageHealth health = adapter.health();
        Assumptions.assumeTrue(health.up(), () -> "FTP indisponibil la " + props.host() + ":" + props.port() + " ("
                + health.detail() + "); test sarit. Porneste stack-ul si docker-compose.dev.yml.");
        return adapter;
    }

    /** Properties from the environment / {@code .env}, or {@code null} if credentials are missing. */
    static FtpProperties properties() {
        Map<String, String> env = environment();
        FtpProperties props = FtpProperties.of(env.getOrDefault("FTP_TEST_HOST", DEFAULT_HOST),
                Integer.parseInt(env.getOrDefault("FTP_TEST_PORT", "21")), env.get("FTP_USER"),
                env.get("FTP_PASSWORD"));
        return props.isConfigured() ? props : null;
    }

    /** Environment variables, completed from the repo-root {@code .env} (the environment wins). */
    static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        Path dotEnv = findDotEnv();
        if (dotEnv != null) {
            try {
                for (String line : Files.readAllLines(dotEnv, StandardCharsets.UTF_8)) {
                    String l = line.strip();
                    int eq = l.indexOf('=');
                    if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                        continue;
                    }
                    String value = l.substring(eq + 1).strip();
                    if (!value.isEmpty()) {
                        values.put(l.substring(0, eq).strip(), value);
                    }
                }
            } catch (IOException e) {
                // Without a readable .env, only the environment variables remain.
            }
        }
        for (String name : List.of("FTP_TEST_HOST", "FTP_TEST_PORT", "FTP_USER", "FTP_PASSWORD")) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                values.put(name, v.strip());
            }
        }
        return values;
    }

    private static Path findDotEnv() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(".env")) && Files.isRegularFile(dir.resolve("docker-compose.yml"))) {
                return dir.resolve(".env");
            }
        }
        return null;
    }

    /**
     * Every file under the FTP server root (full paths), listed over a connection separate from the adapter, so the tests
     * can check what was left on the server (partial chunks, temp names).
     */
    static List<String> listAllFiles(FtpProperties props) throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(2000);
        try {
            ftp.connect(props.host(), props.port());
            if (!ftp.login(props.user(), props.password())) {
                throw new IOException("login FTP refuzat");
            }
            ftp.enterLocalPassiveMode();
            ftp.setPassiveNatWorkaroundStrategy(h -> props.host());
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            List<String> files = new ArrayList<>();
            walk(ftp, "", files);
            return files;
        } finally {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        }
    }

    private static void walk(FTPClient ftp, String dir, List<String> files) throws IOException {
        FTPFile[] entries = ftp.listFiles(dir.isEmpty() ? "/" : dir);
        if (entries == null) {
            return;
        }
        for (FTPFile entry : entries) {
            String name = entry.getName();
            if (name == null || name.equals(".") || name.equals("..")) {
                continue;
            }
            String path = dir + "/" + name;
            if (entry.isDirectory()) {
                walk(ftp, path, files);
            } else {
                files.add(path);
            }
        }
    }

    /** Files left on the server that belong to the key (object, temp names, chunks, manifest). */
    static List<String> filesOf(FtpProperties props, String key) throws IOException {
        return listAllFiles(props).stream().filter(p -> p.contains(key)).toList();
    }

    /**
     * {@link FtpAdapter} that remembers the keys created by tests (to delete them at the end) and can cut the data
     * connection during a write (see {@link #cutNextDataConnectionAfter(long)}).
     */
    static final class TrackingFtpAdapter extends FtpAdapter {

        final FtpProperties props;
        final Set<String> keys = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cutArmed = new AtomicBoolean();
        private volatile long cutAfterBytes;

        TrackingFtpAdapter(FtpProperties props) {
            super(props.host(), props.port(), props.user(), props.password(), props.connectTimeout(),
                    props.controlTimeout(), props.dataTimeout(), props.healthTimeout());
            this.props = props;
        }

        @Override
        public long put(String key, java.io.InputStream data, long length, String contentType) {
            keys.add(key);
            return super.put(key, data, length, contentType);
        }

        @Override
        public String open(String key, long totalSize, int chunkSize) {
            keys.add(key);
            return super.open(key, totalSize, chunkSize);
        }

        /** The first connection that writes more than {@code bytes} bytes is cut abruptly (like a network failure). */
        void cutNextDataConnectionAfter(long bytes) {
            cutAfterBytes = bytes;
            cutArmed.set(true);
        }

        boolean cutWasTriggered() {
            return !cutArmed.get();
        }

        @Override
        protected FTPClient newClient() {
            FTPClient client = new FTPClient();
            client.setSocketFactory(new SocketFactory() {
                @Override
                public Socket createSocket() {
                    return new CuttingSocket();
                }

                @Override
                public Socket createSocket(String host, int port) throws IOException {
                    Socket s = new CuttingSocket();
                    s.connect(new java.net.InetSocketAddress(host, port));
                    return s;
                }

                @Override
                public Socket createSocket(String host, int port, java.net.InetAddress localAddr, int localPort)
                        throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddr,
                        int localPort) throws IOException {
                    throw new UnsupportedOperationException();
                }
            });
            return client;
        }

        /** A socket that, once armed, closes itself after a number of bytes written. */
        private final class CuttingSocket extends Socket {

            @Override
            public OutputStream getOutputStream() throws IOException {
                OutputStream out = super.getOutputStream();
                return new OutputStream() {
                    private long written;

                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        written += len;
                        if (written > cutAfterBytes && cutArmed.compareAndSet(true, false)) {
                            CuttingSocket.this.close();
                            throw new SocketException("conexiune tăiată (simulat)");
                        }
                        out.write(b, off, len);
                    }

                    @Override
                    public void flush() throws IOException {
                        out.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        out.close();
                    }
                };
            }
        }

        /** Deletes the objects and chunks created by tests. */
        void cleanup() {
            for (String key : keys) {
                try {
                    delete(key);
                } catch (RuntimeException e) {
                    System.err.println("cleanup " + key + ": " + e);
                }
            }
        }

        ChunkedUpload upload(String key, String ref, long totalSize, int chunkSize) {
            return new ChunkedUpload(key, ref, totalSize, chunkSize);
        }
    }
}
