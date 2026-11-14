package ro.filegrid.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ro.filegrid.domain.StorageType;
import ro.filegrid.storage.ByteRange;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealth;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageUnavailableException;

class StorageGuardTest {

    private static final StorageType T = StorageType.FTP;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StorageAdapter raw = mock(StorageAdapter.class);
    private StorageGuard guard;
    private StorageAdapter wrapped;

    @BeforeEach
    void setUp() {
        when(raw.type()).thenReturn(T);
        when(raw.health()).thenReturn(StorageHealth.up(T, Duration.ZERO));
        // interval de health foarte mare: verificările se rulează manual cu checkNow
        ResilienceProperties p = new ResilienceProperties(3, Duration.ofMillis(1), 2, 0.5, 50, 4, 4,
                Duration.ofMillis(200), 1, Duration.ofHours(1), Duration.ofSeconds(2), 2);
        guard = new StorageGuard(p, meters);
        wrapped = guard.wrap(raw);
    }

    @AfterEach
    void tearDown() {
        guard.stop();
    }

    @Test
    void retriesReadsOnlyOnUnavailable() {
        when(raw.exists("k")).thenThrow(new StorageUnavailableException(T, "x"))
                .thenThrow(new StorageUnavailableException(T, "x")).thenReturn(true);
        assertThat(wrapped.exists("k")).isTrue();
        verify(raw, times(3)).exists("k");
    }

    @Test
    void doesNotRetryOtherErrorsAndTheyDoNotCountForTheCircuit() {
        when(raw.get(anyString(), any())).thenThrow(new StorageObjectNotFoundException(T, "k"));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> wrapped.get("k", ByteRange.full()))
                    .isInstanceOf(StorageObjectNotFoundException.class);
        }
        verify(raw, times(6)).get(anyString(), any());
        assertThat(guard.circuitState(T)).isEqualTo("CLOSED");
        when(raw.delete("k")).thenThrow(new StorageException(T, "alta"));
        assertThatThrownBy(() -> wrapped.delete("k")).isInstanceOf(StorageException.class);
        verify(raw, times(1)).delete("k");
    }

    @Test
    void putIsNeverRetried() {
        when(raw.put(anyString(), any(), anyLong(), any())).thenThrow(new StorageUnavailableException(T, "x"));
        InputStream body = new ByteArrayInputStream(new byte[4]);
        assertThatThrownBy(() -> wrapped.put("k", body, 4, "a/b")).isInstanceOf(StorageUnavailableException.class);
        verify(raw, times(1)).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void circuitOpensFailsFastThenHalfOpensAndCloses() throws Exception {
        when(raw.put(anyString(), any(), anyLong(), any())).thenThrow(new StorageUnavailableException(T, "x"));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> wrapped.put("k", null, 1, "a/b")).isInstanceOf(StorageUnavailableException.class);
        }
        assertThat(guard.circuitState(T)).isEqualTo("OPEN");
        assertThat(wrapped.health().up()).isFalse();
        // deschis: eșec imediat, fără apel către storage
        assertThatThrownBy(() -> wrapped.put("k", null, 1, "a/b")).isInstanceOf(StorageCircuitOpenException.class);
        assertThatThrownBy(() -> wrapped.exists("k")).isInstanceOf(StorageCircuitOpenException.class);
        verify(raw, times(4)).put(anyString(), any(), anyLong(), any());
        verify(raw, times(0)).exists(anyString());

        Thread.sleep(300); // openWait 200 ms -> HALF_OPEN automat
        assertThat(guard.circuitState(T)).isEqualTo("HALF_OPEN");
        guard.checkNow(T); // health OK în HALF_OPEN = apel de probă reușit
        assertThat(guard.circuitState(T)).isEqualTo("CLOSED");
        assertThat(wrapped.health().up()).isTrue();
    }

    @Test
    void failedProbeInHalfOpenReopens() throws Exception {
        when(raw.health()).thenReturn(StorageHealth.down(T, Duration.ZERO, "refuzat"));
        guard.checkNow(T);
        assertThat(guard.circuitState(T)).isEqualTo("CLOSED"); // o singură verificare căzută nu deschide
        assertThat(wrapped.health().up()).isFalse();
        guard.checkNow(T);
        assertThat(guard.circuitState(T)).isEqualTo("OPEN");
        Thread.sleep(300);
        assertThat(guard.circuitState(T)).isEqualTo("HALF_OPEN");
        guard.checkNow(T);
        assertThat(guard.circuitState(T)).isEqualTo("OPEN");
    }

    @Test
    void healthIsCachedAndGaugeFollowsIt() throws Exception {
        org.mockito.Mockito.clearInvocations(raw);
        wrapped.health();
        wrapped.health();
        verify(raw, times(0)).health(); // cererile nu apelează storage-ul; doar verificarea periodică
        guard.checkNow(T);
        guard.checkNow(T);
        verify(raw, times(2)).health();
        assertThat(meters.get("filegrid.storage.up").tag("storage", "FTP").gauge().value()).isEqualTo(1.0);
        when(raw.health()).thenReturn(StorageHealth.down(T, Duration.ZERO, "x"));
        guard.checkNow(T);
        assertThat(meters.get("filegrid.storage.up").tag("storage", "FTP").gauge().value()).isEqualTo(0.0);
    }
}
