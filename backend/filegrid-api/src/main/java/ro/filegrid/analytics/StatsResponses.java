package ro.filegrid.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

final class StatsResponses {

    private StatsResponses() {
    }

    record Anomalies(Instant generatedAt, Instant from, Instant to, String rule, AnomalyPage anomalies,
                     List<SilentMeter> silentMeters, List<ZoneHourReadings> readingsByZoneHour, String interpretation) {
    }

    record AnomalyPage(List<MeterAnomaly> items, int page, int size, long totalItems, long totalPages) {
    }

    record MeterAnomaly(String meterId, String zone, Instant readingTs, BigDecimal kwh, double median,
                        double stddev, double threshold, double deviations) {
    }

    record SilentMeter(String meterId, String zone, Instant lastReadingTs, long silentMinutes) {
    }

    record ZoneHourReadings(String zone, Instant hour, long readings, BigDecimal totalKwh) {
    }

    record OutageZones(Instant generatedAt, Instant from, Instant to, long totalOutages, List<ZoneRank> ranking,
                       List<ZoneHourOutages> byHour, String interpretation) {
    }

    record ZoneRank(long rank, String zone, long outages, BigDecimal sharePercent, BigDecimal vsAverageRatio,
                    Integer peakHour) {
    }

    record ZoneHourOutages(String zone, int hour, long outages) {
    }

    record OperationStats(Category category, String scenario, long operations, double operationsPerMinute,
                          Double latencyP50Ms, Double latencyP95Ms, Double latencyP99Ms,
                          BigDecimal errorRatePercent, Double p95ChangePercent) {
    }

    record StorageAdvisor(Instant generatedAt, SampleBasis basis, CostAssumption costAssumption,
                          List<StorageScore> matrix, List<StorageRecommendation> recommendations,
                          String interpretation) {
    }

    record SampleBasis(long benchmarkSamples, long liveSamples) {
    }

    record CostAssumption(String unit, BigDecimal FTP, BigDecimal S3, BigDecimal DB) {
    }

    record StorageScore(Category category, StorageType storage, BigDecimal score, Double latencyP50Ms,
                        Double latencyP95Ms, Double errorRatePercent, BigDecimal relativeCostPerGb,
                        long samples, boolean benchmarked) {
    }

    record StorageRecommendation(Category category, StorageType recommendedStorage, StorageType currentPolicyStorage,
                                 boolean matchesPolicy, String rationale) {
    }
}
