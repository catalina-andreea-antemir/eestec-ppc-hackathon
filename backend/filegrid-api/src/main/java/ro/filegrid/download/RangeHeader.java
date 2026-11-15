package ro.filegrid.download;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import ro.filegrid.api.ApiException;
import ro.filegrid.storage.ByteRange;

/**
 * Download {@code Range} header: a single range {@code bytes=a-b}, {@code bytes=a-} or {@code bytes=-n}.
 * Anything else (another unit, multiple ranges, start past the end of the file, {@code b < a}) yields 416.
 */
public final class RangeHeader {

    private static final Pattern SPEC = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private RangeHeader() {
    }

    /** The range resolved against the file size; {@code end} is inclusive. */
    public record Resolved(long start, long end, long total) {

        public long length() {
            return end - start + 1;
        }

        public ByteRange byteRange() {
            return ByteRange.of(start, length());
        }

        /** {@code Content-Range} header value for a 206 response. */
        public String contentRange() {
            return "bytes " + start + "-" + end + "/" + total;
        }
    }

    /** @return the requested range, or empty if there is no header */
    public static Optional<Resolved> parse(String header, long size) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SPEC.matcher(header.strip());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            throw unsatisfiable(size);
        }
        try {
            if (m.group(1).isEmpty()) { // bytes=-n: the last n bytes
                long n = Long.parseLong(m.group(2));
                if (n == 0 || size == 0) {
                    throw unsatisfiable(size);
                }
                return Optional.of(new Resolved(Math.max(0, size - n), size - 1, size));
            }
            long start = Long.parseLong(m.group(1));
            if (start >= size) {
                throw unsatisfiable(size);
            }
            long end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
            if (end < start) {
                throw unsatisfiable(size);
            }
            return Optional.of(new Resolved(start, end, size));
        } catch (NumberFormatException e) {
            throw unsatisfiable(size);
        }
    }

    static ApiException unsatisfiable(long size) {
        return new ApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "RANGE_NOT_SATISFIABLE",
                "Intervalul cerut este în afara fișierului.").withHeader("Content-Range", "bytes */" + size);
    }
}
