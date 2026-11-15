package ro.filegrid.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.filegrid.analytics.StatsResponses.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.filegrid.analytics.StatsRepository.ScenarioActivity;
import ro.filegrid.domain.Category;

class StatsServicePeakVsNormalTest {

    private static final Instant T = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void windowSpansAllCategoriesOfTheScenarioWithAnExclusiveEndAndOneMinuteMinimum() {
        var activity = List.of(
                new ScenarioActivity("storm", Category.METER_READING, T.plusSeconds(60), T.plusSeconds(900), 0),
                new ScenarioActivity("storm", Category.OUTAGE_RECORD, T, T.plusSeconds(600), 3),
                new ScenarioActivity("normal", Category.OUTAGE_RECORD, T.minusSeconds(5), T.minusSeconds(5), 0));
        assertThat(StatsService.window("storm", activity, T.minusSeconds(86400), T))
                .isEqualTo(new ScenarioWindow("storm", T, T.plusSeconds(900).plusNanos(100)));
        assertThat(StatsService.window("normal", activity, T.minusSeconds(86400), T))
                .isEqualTo(new ScenarioWindow("normal", T.minusSeconds(5), T.plusSeconds(55)));
        assertThat(StatsService.window("peak", activity, T.minusSeconds(86400), T))
                .isEqualTo(new ScenarioWindow("peak", T.minusSeconds(86400), T));
    }

    @Test
    void interpretationWithoutGrowthOrRejectionsSaysSo() {
        var same = new ScenarioStats(10, 1.0, 5, 9, 12, 0, 0);
        var text = StatsService.interpret("normal", "storm",
                List.of(new CategoryComparison(Category.METER_READING, same, same, 0.0)));
        assertThat(text).isEqualTo("În scenariul „storm” volumul de cereri nu a crescut față de „normal” în nicio "
                + "categorie. Bulkhead-ul nu a respins nicio cerere.");
    }

    @Test
    void comparisonRoundsToOneDecimalAndKeepsRejectionsOutOfRequests() {
        var rows = new java.util.ArrayList<OperationStats>();
        var activity = new java.util.ArrayList<ScenarioActivity>();
        for (Category c : Category.values()) {
            rows.add(new OperationStats(c, "normal", 100, 3.33333, 9.04, 23.06, 41.0, new java.math.BigDecimal("0"),
                    null));
            rows.add(new OperationStats(c, "storm", 200, 6.66666, 9.0, 24.0, 44.0, new java.math.BigDecimal("1.25"),
                    4.0763));
            activity.add(new ScenarioActivity("storm", c, T, T.plusSeconds(1800), c == Category.CONNECTION_DOC ? 7 : 0));
        }
        var result = StatsService.compare(T, new ScenarioWindow("normal", T, T), new ScenarioWindow("storm", T, T),
                rows, activity);
        var doc = result.categories().get(2);
        assertThat(doc.category()).isEqualTo(Category.CONNECTION_DOC);
        assertThat(doc.normal()).isEqualTo(new ScenarioStats(100, 3.3, 9.0, 23.1, 41.0, 0.0, 0));
        assertThat(doc.peak()).isEqualTo(new ScenarioStats(200, 6.7, 9.0, 24.0, 44.0, 1.3, 7));
        assertThat(doc.p95ChangePercent()).isEqualTo(4.1);
        assertThat(result.interpretation()).contains("de 2,0 ori").contains("(+4,1%)")
                .contains("Bulkhead-ul a respins 7 cereri în „storm” (CONNECTION_DOC: 7) și 0 în „normal”");
    }
}
