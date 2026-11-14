package ro.filegrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void upWhenFileGridMetaAnswers() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);

        ResponseEntity<HealthController.Health> r = controller(Clock.systemUTC()).health();

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().status()).isEqualTo("UP");
        verify(connection).close();
    }

    @Test
    void downWith503AndRetryAfterWhenFileGridMetaFails() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("timeout"));

        ResponseEntity<HealthController.Health> r = controller(Clock.systemUTC()).health();

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getBody().status()).isEqualTo("DOWN");
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void resultIsReusedForOneSecond() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
        HealthController controller = controller(Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC));

        controller.health();
        controller.health();

        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void upWithoutADatabase() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> none = mock(ObjectProvider.class);

        assertThat(new HealthController(none, Clock.systemUTC()).health().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HealthController controller(Clock clock) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return new HealthController(provider, clock);
    }
}
