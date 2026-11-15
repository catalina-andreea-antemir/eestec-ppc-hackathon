package ro.filegrid.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.filegrid.api.ApiException;
import ro.filegrid.storage.ByteRange;

/** Range din header și link-ul semnat (semnătură, expirare, modificare). */
class RangeAndTokenTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[48]);

    // --- Range ------------------------------------------------------------------------------------------------

    @Test
    void noHeaderMeansWholeFile() {
        assertThat(RangeHeader.parse(null, 100)).isEmpty();
        assertThat(RangeHeader.parse("  ", 100)).isEmpty();
    }

    @Test
    void parsesTheThreeForms() {
        RangeHeader.Resolved closed = RangeHeader.parse("bytes=10-19", 100).orElseThrow();
        assertThat(closed.byteRange()).isEqualTo(ByteRange.of(10, 10));
        assertThat(closed.contentRange()).isEqualTo("bytes 10-19/100");

        RangeHeader.Resolved open = RangeHeader.parse("bytes=90-", 100).orElseThrow();
        assertThat(open.byteRange()).isEqualTo(ByteRange.of(90, 10));
        assertThat(open.contentRange()).isEqualTo("bytes 90-99/100");

        RangeHeader.Resolved suffix = RangeHeader.parse("bytes=-30", 100).orElseThrow();
        assertThat(suffix.byteRange()).isEqualTo(ByteRange.of(70, 30));
        assertThat(suffix.contentRange()).isEqualTo("bytes 70-99/100");
    }

    @Test
    void clampsEndAndOversizedSuffix() {
        assertThat(RangeHeader.parse("bytes=50-5000", 100).orElseThrow().contentRange()).isEqualTo("bytes 50-99/100");
        assertThat(RangeHeader.parse("bytes=-500", 100).orElseThrow().contentRange()).isEqualTo("bytes 0-99/100");
        assertThat(RangeHeader.parse("bytes=0-0", 100).orElseThrow().length()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=100-", "bytes=100-200", "bytes=20-10", "bytes=-0", "bytes=-", "bytes=a-b",
            "items=0-5", "bytes=0-5,10-15", "0-5", "bytes=99999999999999999999-"})
    void invalidOrOutsideRangeGives416WithContentRange(String header) {
        assertThatThrownBy(() -> RangeHeader.parse(header, 100)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(416);
            assertThat(e.code()).isEqualTo("RANGE_NOT_SATISFIABLE");
            assertThat(e.headers()).containsEntry("Content-Range", "bytes */100");
        });
    }

    // --- token ------------------------------------------------------------------------------------------------

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void tokenRoundTripsAndIsValidFor15Minutes() {
        MutableClock clock = new MutableClock();
        DownloadTokenService tokens = new DownloadTokenService(SECRET, Duration.ofMinutes(15), clock);
        UUID file = UUID.randomUUID();

        DownloadTokenService.Link link = tokens.create(file, "dispecer");

        assertThat(link.expiresAt()).isEqualTo(Instant.parse("2026-11-14T10:15:00Z"));
        assertThat(link.token()).doesNotContain("dispecer").matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(new String(Base64.getUrlDecoder().decode(link.token().split("\\.")[0]))).doesNotContain("dispecer");
        clock.now = clock.now.plus(Duration.ofMinutes(14)).plusSeconds(59);
        assertThat(tokens.verify(link.token()).fileId()).isEqualTo(file);
        clock.now = clock.now.plusSeconds(1);
        assertThatThrownBy(() -> tokens.verify(link.token())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(401));
    }

    @Test
    void tokenBoundToUserDiffers() {
        DownloadTokenService tokens = new DownloadTokenService(SECRET, Duration.ofMinutes(15), new MutableClock());
        UUID file = UUID.randomUUID();
        assertThat(tokens.verify(tokens.create(file, "a").token()).userTag())
                .isNotEqualTo(tokens.verify(tokens.create(file, "b").token()).userTag());
    }

    @Test
    void modifiedOrForeignTokensGive401() {
        MutableClock clock = new MutableClock();
        DownloadTokenService tokens = new DownloadTokenService(SECRET, Duration.ofMinutes(15), clock);
        DownloadTokenService other = new DownloadTokenService(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4,
                5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                32}), Duration.ofMinutes(15), clock);
        UUID file = UUID.randomUUID();
        String token = tokens.create(file, "x").token();
        String[] parts = token.split("\\.");
        String otherFile = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"f\":\"" + UUID.randomUUID() + "\",\"u\":\"x\",\"e\":" + clock.now.plusSeconds(900).getEpochSecond()
                        + "}").getBytes());
        // Penultimul caracter: toți cei 6 biți sunt semnificativi (ultimul are biți de umplere și poate decoda identic).
        String head = token.substring(0, token.length() - 2);
        String flipped = head + (token.charAt(token.length() - 2) == 'A' ? 'B' : 'A') + token.charAt(token.length() - 1);

        for (String bad : new String[]{flipped, otherFile + "." + parts[1], parts[0] + "." + parts[1] + "x",
                other.create(file, "x").token(), "", "abc", ".", "a.b.c", parts[0], "x".repeat(600)}) {
            assertThatThrownBy(() -> tokens.verify(bad)).as(bad).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status().value()).isEqualTo(401));
        }
    }

    @Test
    void shortOrMissingSecretIsRefused() {
        assertThatThrownBy(() -> new DownloadTokenService("", Duration.ofMinutes(15), new MutableClock()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DownloadTokenService("scurt", Duration.ofMinutes(15), new MutableClock()))
                .isInstanceOf(IllegalStateException.class);
    }
}
