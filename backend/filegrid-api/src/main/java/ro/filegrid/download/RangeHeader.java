package ro.filegrid.download;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import ro.filegrid.api.ApiException;
import ro.filegrid.storage.ByteRange;

/**
 * Header-ul {@code Range} al descărcării: un singur interval {@code bytes=a-b}, {@code bytes=a-} sau {@code bytes=-n}.
 * Orice altceva (altă unitate, mai multe intervale, început după sfârșitul fișierului, {@code b < a}) dă 416.
 */
public final class RangeHeader {

    private static final Pattern SPEC = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private RangeHeader() {
    }

    /** Intervalul rezolvat față de mărimea fișierului; {@code end} e inclusiv. */
    public record Resolved(long start, long end, long total) {

        public long length() {
            return end - start + 1;
        }

        public ByteRange byteRange() {
            return ByteRange.of(start, length());
        }

        /** Valoarea header-ului {@code Content-Range} al unui răspuns 206. */
        public String contentRange() {
            return "bytes " + start + "-" + end + "/" + total;
        }
    }

    /** @return intervalul cerut, sau gol dacă nu există header */
    public static Optional<Resolved> parse(String header, long size) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SPEC.matcher(header.strip());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            throw unsatisfiable(size);
        }
        try {
            if (m.group(1).isEmpty()) { // bytes=-n: ultimii n octeți
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
