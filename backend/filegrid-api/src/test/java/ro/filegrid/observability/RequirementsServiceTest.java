package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ro.filegrid.domain.Category;
import ro.filegrid.ingest.DocumentEncryption;
import ro.filegrid.observability.RequirementsService.Measurements;
import ro.filegrid.observability.RequirementsService.OutageLatency;
import ro.filegrid.observability.RequirementsService.Requirement;
import ro.filegrid.policy.CategoryPolicy;
import ro.filegrid.policy.PolicyEngine;

class RequirementsServiceTest {

    /** Pragurile reale din requirements.yml. */
    private final RequirementsService.Thresholds loaded = RequirementsService.Thresholds.load();
    /** Pentru logica lui minSamples testele folosesc explicit 5 măsurători (independent de valoarea din fișier). */
    private final RequirementsService.Thresholds thresholds = new RequirementsService.Thresholds(
            loaded.window(), 5, loaded.unconfirmedMax(), loaded.p95MaxSeconds(), loaded.deniedMax(),
            loaded.failedMaxPercent());

    private static class Fake implements Measurements {
        long unconfirmed;
        OutageLatency outage = new OutageLatency(null, 0);
        long denied;
        long[] video = {0, 0};

        @Override public long meterUnconfirmed() { return unconfirmed; }
        @Override public OutageLatency outageLatency(Duration w) { return outage; }
        @Override public long documentAccessDenied(Duration w) { return denied; }
        @Override public long[] videoTransfers(Duration w) { return video; }
    }

    private Map<String, Requirement> evaluate(Fake fake, boolean encrypted) {
        PolicyEngine policies = mock(PolicyEngine.class);
        CategoryPolicy doc = mock(CategoryPolicy.class);
        when(doc.encrypted()).thenReturn(encrypted);
        when(policies.policy(Category.CONNECTION_DOC)).thenReturn(doc);
        var result = new RequirementsService(fake, policies, mock(DocumentEncryption.class), thresholds).evaluate();
        assertThat(result.requirements()).hasSize(4);
        return result.requirements().stream().collect(java.util.stream.Collectors.toMap(Requirement::id, r -> r));
    }

    private Requirement r2(long samples, Double p95) {
        Fake f = new Fake();
        f.outage = new OutageLatency(p95, samples);
        return evaluate(f, true).get("R2");
    }

    private Requirement r4(long total, long failed) {
        Fake f = new Fake();
        f.video = new long[] {total, failed};
        return evaluate(f, true).get("R4");
    }

    @Test
    void loadsThePlanThresholds() {
        assertThat(loaded.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(loaded.minSamples()).isEqualTo(1);
        assertThat(loaded.unconfirmedMax()).isZero();
        assertThat(loaded.p95MaxSeconds()).isEqualTo(2.0);
        assertThat(loaded.deniedMax()).isZero();
        assertThat(loaded.failedMaxPercent()).isEqualTo(5.0);
    }

    @Test
    void withTheConfiguredMinimumOneUploadIsEnoughToEvaluate() {
        Fake outage = new Fake();
        outage.outage = new OutageLatency(0.05, 1);
        Fake video = new Fake();
        video.video = new long[] {1, 0};
        var policies = mock(PolicyEngine.class);
        var doc = mock(CategoryPolicy.class);
        when(doc.encrypted()).thenReturn(true);
        when(policies.policy(Category.CONNECTION_DOC)).thenReturn(doc);
        Requirement r2 = new RequirementsService(outage, policies, mock(DocumentEncryption.class), loaded)
                .evaluate().requirements().stream().filter(r -> r.id().equals("R2")).findFirst().orElseThrow();
        Requirement r4 = new RequirementsService(video, policies, mock(DocumentEncryption.class), loaded)
                .evaluate().requirements().stream().filter(r -> r.id().equals("R4")).findFirst().orElseThrow();
        assertThat(r2.status()).isEqualTo("OK");
        assertThat(r2.current()).containsEntry("sufficientData", true);
        assertThat(r4.status()).isEqualTo("OK");
        assertThat(r4.current()).containsEntry("sufficientData", true);
    }

    @Test
    void everythingGreenWhenQuiet() {
        var r = evaluate(new Fake(), true);
        assertThat(r.values()).allSatisfy(x -> assertThat(x.status()).isEqualTo("OK"));
        assertThat(r.get("R1").category()).isEqualTo(Category.METER_READING);
        assertThat(r.get("R4").category()).isEqualTo(Category.INSPECTION_VIDEO);
    }

    @Test
    void r1UsesOnlyUnconfirmedAndNeverReportsAbandoned() {
        Fake f = new Fake();
        f.unconfirmed = 1;
        Requirement r1 = evaluate(f, true).get("R1");
        assertThat(r1.status()).isEqualTo("AT_RISK");
        assertThat(r1.current()).containsOnlyKeys("unconfirmed").containsEntry("unconfirmed", 1L);
        assertThat(r1.thresholds()).containsOnlyKeys("unconfirmedMax").containsEntry("unconfirmedMax", 0L);
        assertThat(r1.message()).doesNotContainIgnoringCase("abandon");
        Requirement ok = evaluate(new Fake(), true).get("R1");
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.current()).doesNotContainKey("abandoned");
    }

    // --- R2 ----------------------------------------------------------------------------------------------------

    @Test
    void r2WithoutAnyMeasurementSaysItCouldNotBeVerified() {
        Requirement r = r2(0, null);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.message()).contains("nu a putut fi verificată");
        assertThat(r.current()).containsEntry("samples", 0L).containsEntry("sufficientData", false);
        assertThat(r.current().get("p95Seconds")).isNull();
    }

    @Test
    void r2OneAndFourSamplesAreInsufficientEvenWhenSlow() {
        for (long n : new long[] {1, 4}) {
            Requirement r = r2(n, 9.0); // mult peste 2 s, dar prea puține cereri
            assertThat(r.status()).as("n=" + n).isEqualTo("OK");
            assertThat(r.message()).contains("Date insuficiente: " + n + " din minim 5");
            assertThat(r.current()).containsEntry("samples", n).containsEntry("sufficientData", false);
        }
    }

    @Test
    void r2FiveSamplesAreEvaluatedAndSlowStaysAtRisk() {
        Requirement slow = r2(5, 2.4);
        assertThat(slow.status()).isEqualTo("AT_RISK");
        assertThat(slow.current()).containsEntry("samples", 5L).containsEntry("sufficientData", true)
                .containsEntry("p95Seconds", 2.4);
        assertThat(slow.message()).doesNotContain("insuficiente");
        assertThat(r2(5, 1.99).status()).isEqualTo("OK");
        assertThat(r2(5, 2.0).status()).isEqualTo("AT_RISK"); // pragul e exclusiv
        assertThat(r2(5, 1.99).current()).containsEntry("sufficientData", true);
    }

    @Test
    void r2KeysMatchTheContract() {
        Requirement r = r2(5, 1.0);
        assertThat(r.thresholds()).containsKeys("p95MaxSeconds", "windowMinutes", "minSamples");
        assertThat(r.current()).containsKeys("p95Seconds", "samples", "sufficientData");
    }

    // --- R3 ----------------------------------------------------------------------------------------------------

    @Test
    void r3AtRiskWhenEncryptionOffOrAnyAccessDenied() {
        assertThat(evaluate(new Fake(), false).get("R3").status()).isEqualTo("AT_RISK");
        Fake f = new Fake();
        f.denied = 1;
        Requirement r3 = evaluate(f, true).get("R3");
        assertThat(r3.status()).isEqualTo("AT_RISK");
        assertThat(r3.thresholds()).containsKeys("encryptionRequired", "deniedMax", "windowMinutes");
        assertThat(r3.current()).containsEntry("denied", 1L).containsEntry("encryptionActive", true);
    }

    // --- R4 ----------------------------------------------------------------------------------------------------

    @Test
    void r4WithoutAnyTransferSaysItCouldNotBeVerified() {
        Requirement r = r4(0, 0);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.message()).contains("nu a putut fi verificată");
        assertThat(r.current()).containsEntry("samples", 0L).containsEntry("sufficientData", false);
    }

    @Test
    void r4OneFailureOutOfFourIsInsufficientDataAndOk() {
        Requirement r = r4(4, 1); // 25%, dar sub minim
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.message()).contains("Date insuficiente: 4 din minim 5");
        assertThat(r.current()).containsEntry("samples", 4L).containsEntry("sufficientData", false)
                .containsEntry("failed", 1L);
    }

    @Test
    void r4OneTotalAndOneFailureIsStillInsufficient() {
        Requirement r = r4(1, 1);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.current()).containsEntry("sufficientData", false);
    }

    @Test
    void r4OneFailureOutOfFiveIsTwentyPercentAndAtRisk() {
        Requirement r = r4(5, 1);
        assertThat(r.status()).isEqualTo("AT_RISK");
        assertThat(r.current()).containsEntry("failedPercent", 20.0).containsEntry("transfers", 5L)
                .containsEntry("samples", 5L).containsEntry("sufficientData", true);
        assertThat(r.message()).doesNotContain("insuficiente");
    }

    @Test
    void r4NoFailuresOutOfFiveIsOk() {
        Requirement r = r4(5, 0);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.current()).containsEntry("failedPercent", 0.0).containsEntry("sufficientData", true);
    }

    @Test
    void r4FiveFailuresOutOfOneHundredIsAtRiskAndFourIsOk() {
        assertThat(r4(100, 4).status()).isEqualTo("OK");
        assertThat(r4(100, 5).status()).isEqualTo("AT_RISK"); // pragul de 5% e exclusiv
    }

    @Test
    void r4KeysMatchTheContract() {
        Requirement r = r4(9, 0);
        assertThat(r.thresholds()).containsKeys("failedMaxPercent", "windowMinutes", "minSamples");
        assertThat(r.current()).containsKeys("failedPercent", "transfers", "samples", "sufficientData");
    }
}
