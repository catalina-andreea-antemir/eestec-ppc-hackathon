package ro.filegrid.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ro.filegrid.analytics.StatsResponses.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import ro.filegrid.TestSecrets;
import ro.filegrid.analytics.StatsRepository.Outages;
import ro.filegrid.analytics.StatsRepository.ScenarioActivity;
import ro.filegrid.analytics.StatsRepository.StorageMeasurement;
import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;
import ro.filegrid.security.Role;
import ro.filegrid.security.TokenService;

@SpringBootTest
@AutoConfigureMockMvc
class StatsApiTest {

    private static final String STATS = "/api/v1/stats/";
    private static final Instant FROM = Instant.parse("2026-11-14T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-14T06:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    @MockitoBean StatsRepository repository;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        TestSecrets.register(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anomalies", "outage-zones", "peak-vs-normal", "storage-advisor"})
    void endpointsRequireARealAccessTokenAndAnAllowedRole(String endpoint) throws Exception {
        mvc.perform(get(STATS + endpoint)).andExpect(error(401, "UNAUTHORIZED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get(STATS + endpoint).header("Authorization", "Bearer invalid"))
                .andExpect(error(401, "UNAUTHORIZED"));
        for (Role role : List.of(Role.FIELD, Role.CONNECTIONS, Role.METER_SERVICE)) {
            mvc.perform(get(STATS + endpoint).header("Authorization", bearer(role)))
                    .andExpect(error(403, "FORBIDDEN"));
        }
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({
        "anomalies, from, yesterday",
        "anomalies, to, 2026-11-14T06:00:00",
        "anomalies, to, 2026-11-14T06:00:00.000000001Z",
        "anomalies, from, 0000-12-31T00:00:00Z",
        "anomalies, page, 0",
        "anomalies, page, 2147483648",
        "anomalies, size, 101",
        "anomalies, size, 2.5",
        "anomalies, silentMinutes, -1",
        "outage-zones, from, ''",
        "peak-vs-normal, normalScenario, ''",
        "peak-vs-normal, peakScenario, STORM",
        "peak-vs-normal, peakScenario, normal",
        "peak-vs-normal, peakScenario, stormstormstormstormstormstorm123"
    })
    void invalidParametersReturnJsonWithoutReadingTheDatabase(String endpoint, String parameter, String value)
            throws Exception {
        mvc.perform(get(STATS + endpoint).header("Authorization", bearer(Role.ADMIN)).param(parameter, value))
                .andExpect(error(400, "VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value(parameter));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anomalies", "outage-zones"})
    void equalInstantsWithDifferentOffsetsAreRejected(String endpoint) throws Exception {
        mvc.perform(get(STATS + endpoint).header("Authorization", bearer(Role.DISPATCH))
                        .param("from", "2026-11-14T08:00:00+02:00").param("to", TO.toString()))
                .andExpect(error(400, "VALIDATION_ERROR")).andExpect(jsonPath("$.details.field").value("from"));
        verifyNoInteractions(repository);
    }

    @Test
    void anomaliesNormalizeOffsetsAndRetainTotalOnOutOfRangePage() throws Exception {
        when(repository.anomalies(FROM, TO, 3, 1)).thenReturn(new AnomalyPage(List.of(), 3, 1, 2, 2));
        when(repository.silentMeters(TO, 60)).thenReturn(List.of(new SilentMeter("meter1", "Banat", FROM, 64)));
        when(repository.consumptionByHour(FROM, TO))
                .thenReturn(List.of(new ZoneHourReadings("Banat", FROM, 5, new BigDecimal("10.5"))));
        mvc.perform(get(STATS + "anomalies").header("Authorization", bearer(Role.DISPATCH))
                        .param("from", "2026-11-14T02:00:00+02:00").param("to", TO.toString())
                        .param("page", "3").param("size", "1"))
                .andExpect(status().isOk()).andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.generatedAt").isString()).andExpect(jsonPath("$.from").value(FROM.toString()))
                .andExpect(jsonPath("$.to").value(TO.toString())).andExpect(jsonPath("$.rule").isString())
                .andExpect(jsonPath("$.anomalies.items").isEmpty()).andExpect(jsonPath("$.anomalies.page").value(3))
                .andExpect(jsonPath("$.anomalies.size").value(1)).andExpect(jsonPath("$.anomalies.totalItems").value(2))
                .andExpect(jsonPath("$.anomalies.totalPages").value(2))
                .andExpect(jsonPath("$.silentMeters[0].lastReadingTs").value(FROM.toString()))
                .andExpect(jsonPath("$.silentMeters[0].silentMinutes").value(64))
                .andExpect(jsonPath("$.readingsByZoneHour[0].hour").value(FROM.toString()))
                .andExpect(jsonPath("$.readingsByZoneHour[0].readings").value(5))
                .andExpect(jsonPath("$.readingsByZoneHour[0].totalKwh").value(10.5))
                .andExpect(jsonPath("$.interpretation").isString());
    }

    @Test
    void emptyAnomaliesReturnArraysAndZeroTotals() throws Exception {
        when(repository.anomalies(FROM, TO, 1, 20)).thenReturn(new AnomalyPage(List.of(), 1, 20, 0, 0));
        mvc.perform(get(STATS + "anomalies").header("Authorization", bearer(Role.ADMIN))
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.anomalies.items").isEmpty())
                .andExpect(jsonPath("$.anomalies.totalItems").value(0)).andExpect(jsonPath("$.anomalies.totalPages").value(0))
                .andExpect(jsonPath("$.silentMeters").isEmpty()).andExpect(jsonPath("$.readingsByZoneHour").isEmpty());
    }

    @Test
    void outageResponsePreservesUndefinedPeakAndIncludesDistribution() throws Exception {
        when(repository.outageZones(FROM, TO)).thenReturn(new Outages(List.of(
                new ZoneRank(1, "Banat", 2, BigDecimal.valueOf(100), BigDecimal.valueOf(3), 5),
                new ZoneRank(2, "Dobrogea", 0, BigDecimal.ZERO, BigDecimal.ZERO, null),
                new ZoneRank(2, "Muntenia", 0, BigDecimal.ZERO, BigDecimal.ZERO, null)),
                List.of(new ZoneHourOutages("Banat", 5, 2))));
        mvc.perform(get(STATS + "outage-zones").header("Authorization", bearer(Role.ADMIN))
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk()).andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.totalOutages").value(2)).andExpect(jsonPath("$.ranking.length()").value(3))
                .andExpect(jsonPath("$.ranking[0].peakHour").value(5))
                .andExpect(jsonPath("$.ranking[1].peakHour").value(nullValue()))
                .andExpect(jsonPath("$.byHour[0].hour").value(5)).andExpect(jsonPath("$.byHour[0].outages").value(2))
                .andExpect(jsonPath("$.interpretation").isString());
    }

    @Test
    void emptyOutageWindowKeepsRanksAndLeavesUndefinedValuesNull() throws Exception {
        when(repository.outageZones(any(), eq(TO))).thenReturn(new Outages(
                List.of("Banat", "Dobrogea", "Muntenia").stream()
                        .map(zone -> new ZoneRank(1, zone, 0, null, null, null)).toList(), List.of()));
        mvc.perform(get(STATS + "outage-zones").header("Authorization", bearer(Role.DISPATCH)).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutages").value(0)).andExpect(jsonPath("$.ranking.length()").value(3))
                .andExpect(jsonPath("$.ranking[0].rank").value(1))
                .andExpect(jsonPath("$.ranking[0].sharePercent").value(nullValue()))
                .andExpect(jsonPath("$.ranking[0].vsAverageRatio").value(nullValue()))
                .andExpect(jsonPath("$.ranking[0].peakHour").value(nullValue()))
                .andExpect(jsonPath("$.byHour").isEmpty())
                .andExpect(jsonPath("$.interpretation").value("În intervalul selectat nu s-au înregistrat avarii."));
        verify(repository).outageZones(TO.minus(Duration.ofDays(30)), TO);
    }

    @Test
    void peakVsNormalReturnsMeasuredFiguresPerCategoryInTheRunWindows() throws Exception {
        Instant normalStart = Instant.parse("2026-11-14T10:00:00Z");
        Instant stormStart = Instant.parse("2026-11-14T11:00:00Z");
        var activity = new ArrayList<ScenarioActivity>();
        var rows = new ArrayList<OperationStats>();
        for (Category category : Category.values()) {
            activity.add(new ScenarioActivity("normal", category, normalStart, normalStart.plusSeconds(1800), 0));
            activity.add(new ScenarioActivity("storm", category, stormStart, stormStart.plusSeconds(1800),
                    category == Category.OUTAGE_RECORD ? 14 : 0));
            rows.add(new OperationStats(category, "normal", 36, 1.2, 190.0, 340.0, 410.0, BigDecimal.ZERO, null));
            rows.add(new OperationStats(category, "storm", category == Category.OUTAGE_RECORD ? 480 : 36,
                    category == Category.OUTAGE_RECORD ? 16.0 : 1.2, 260.0,
                    category == Category.OUTAGE_RECORD ? 710.0 : 350.0, 1250.0, new BigDecimal("0.625"),
                    category == Category.OUTAGE_RECORD ? 108.82352 : 2.94));
        }
        when(repository.scenarioActivity(eq("normal"), eq("storm"), any(), any())).thenReturn(activity);
        when(repository.peakVsNormal(eq("normal"), any(), any(), eq("storm"), any(), any())).thenReturn(rows);
        mvc.perform(get(STATS + "peak-vs-normal").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normal.scenario").value("normal"))
                .andExpect(jsonPath("$.normal.from").value("2026-11-14T10:00:00Z"))
                .andExpect(jsonPath("$.peak.scenario").value("storm"))
                .andExpect(jsonPath("$.peak.from").value("2026-11-14T11:00:00Z"))
                .andExpect(jsonPath("$.categories.length()").value(4))
                .andExpect(jsonPath("$.categories[1].category").value("OUTAGE_RECORD"))
                .andExpect(jsonPath("$.categories[1].normal.requests").value(36))
                .andExpect(jsonPath("$.categories[1].peak.requests").value(480))
                .andExpect(jsonPath("$.categories[1].peak.throughputPerMinute").value(16.0))
                .andExpect(jsonPath("$.categories[1].peak.latencyP95Ms").value(710.0))
                .andExpect(jsonPath("$.categories[1].peak.errorRatePercent").value(0.6))
                .andExpect(jsonPath("$.categories[1].peak.rejected").value(14))
                .andExpect(jsonPath("$.categories[1].normal.rejected").value(0))
                .andExpect(jsonPath("$.categories[1].p95ChangePercent").value(108.8))
                .andExpect(jsonPath("$.interpretation").value(org.hamcrest.Matchers.containsString(
                        "OUTAGE_RECORD a avut cea mai mare creștere a cererilor (de la 36 la 480")))
                .andExpect(jsonPath("$.interpretation").value(org.hamcrest.Matchers.containsString(
                        "Bulkhead-ul a respins 14 cereri în „storm” (OUTAGE_RECORD: 14)")));
        // Each scenario's real window (first-last row, exclusive end) reaches query 05.
        verify(repository).peakVsNormal("normal", normalStart, normalStart.plusSeconds(1800).plusNanos(100),
                "storm", stormStart, stormStart.plusSeconds(1800).plusNanos(100));
    }

    @Test
    void reportsUnavailablePerformanceFieldsAndIncompleteStorageEvidence() throws Exception {
        when(repository.peakVsNormal(eq("normal"), any(), any(), eq("peak"), any(), any()))
                .thenReturn(List.of(new OperationStats(Category.OUTAGE_RECORD, "peak", 0, 0,
                        null, null, null, null, null)));
        mvc.perform(get(STATS + "peak-vs-normal").header("Authorization", bearer(Role.DISPATCH))
                        .param("peakScenario", "peak"))
                .andExpect(unavailable("REQUEST_METRICS_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.missing[0].category").value("METER_READING"))
                .andExpect(jsonPath("$.details.missing[1].scenario").value("peak"))
                .andExpect(jsonPath("$.details.missing[1].fields", hasItems("latencyP95Ms", "p95ChangePercent")));
        verify(repository).scenarioActivity(eq("normal"), eq("peak"), any(), any());
        verify(repository).peakVsNormal(eq("normal"), any(), any(), eq("peak"), any(), any());
        mvc.perform(get(STATS + "storage-advisor").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(unavailable("STORAGE_MEASUREMENTS_INCOMPLETE"));
    }

    @Test
    void defaultAnomalyWindowUsesOneUtcReferenceTime() throws Exception {
        when(repository.anomalies(any(), any(), eq(1), eq(20))).thenReturn(new AnomalyPage(List.of(), 1, 20, 0, 0));
        var response = mvc.perform(get(STATS + "anomalies").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse();
        var payload = json.readTree(response.getContentAsByteArray());
        Instant generated = Instant.parse(payload.get("generatedAt").asText());
        Instant from = Instant.parse(payload.get("from").asText());
        assertThat(payload.get("to").asText()).isEqualTo(generated.toString());
        assertThat(Duration.between(from, generated)).isEqualTo(Duration.ofDays(1));
        verify(repository).anomalies(from, generated, 1, 20);
        verify(repository).silentMeters(generated, 60);
    }

    @Test
    void metadataDatabaseFailureReturnsTracedServiceUnavailable() throws Exception {
        when(repository.outageZones(any(), any())).thenThrow(new DataAccessResourceFailureException("SQL offline"));
        mvc.perform(get(STATS + "outage-zones").header("Authorization", bearer(Role.DISPATCH)))
                .andExpect(unavailable("METADATA_DB_UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 11})
    void storageAdvisorRequiresBenchmarkEvidenceInEveryCell(int benchmarkCells) throws Exception {
        when(repository.storageScores(any(), any())).thenReturn(storageMatrix(benchmarkCells));
        mvc.perform(get(STATS + "storage-advisor").header("Authorization", bearer(Role.DISPATCH)))
                .andExpect(unavailable("STORAGE_MEASUREMENTS_INCOMPLETE"))
                .andExpect(jsonPath("$.recommendations").doesNotExist());
    }

    @Test
    void completeBenchmarkMatrixCanReturnAdviceWithoutLiveSamples() throws Exception {
        when(repository.storageScores(any(), any())).thenReturn(storageMatrix(12));
        mvc.perform(get(STATS + "storage-advisor").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.matrix.length()").value(12))
                .andExpect(jsonPath("$.basis.benchmarkSamples").value(12))
                .andExpect(jsonPath("$.basis.liveSamples").value(0))
                .andExpect(jsonPath("$.recommendations.length()").value(4))
                .andExpect(jsonPath("$.interpretation").isNotEmpty());
    }

    private List<StorageMeasurement> storageMatrix(int benchmarkCells) {
        var cells = new ArrayList<StorageMeasurement>();
        for (Category category : Category.values()) {
            for (StorageType storage : StorageType.values()) {
                boolean benchmarked = cells.size() < benchmarkCells;
                var score = new StorageScore(category, storage, BigDecimal.valueOf(90), 10.0, 10.0, 0.0,
                        BigDecimal.ONE, 1, benchmarked);
                cells.add(new StorageMeasurement(score, 1L, benchmarked ? 1 : 0, benchmarked ? 0 : 1));
            }
        }
        return cells;
    }

    private String bearer(Role role) {
        return "Bearer " + tokens.issueAccessToken("stats-test", role);
    }

    private ResultMatcher error(int statusCode, String code) {
        return result -> {
            status().is(statusCode).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.status").value(statusCode).match(result);
            jsonPath("$.message").isNotEmpty().match(result);
            header().exists("X-Trace-Id").match(result);
            jsonPath("$.traceId").value(result.getResponse().getHeader("X-Trace-Id")).match(result);
        };
    }

    private ResultMatcher unavailable(String reason) {
        return result -> {
            error(503, "STORAGE_UNAVAILABLE").match(result);
            jsonPath("$.details.reason").value(reason).match(result);
            var payload = json.readTree(result.getResponse().getContentAsByteArray());
            header().string("Retry-After", payload.get("retryAfterSeconds").asText()).match(result);
        };
    }
}
