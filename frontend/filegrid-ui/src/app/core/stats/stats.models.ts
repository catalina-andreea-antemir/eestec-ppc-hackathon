// API contract types for statistics (docs/api/openapi.yaml: StorageAdvisor, StorageScore,
// PeakVsNormal, ScenarioWindow, ScenarioStats, Anomalies, OutageZones, Requirements).
// Every response carries a Romanian sentence for decision makers in its `interpretation` field.

import { Role } from '../auth/auth.models';
import { Category, StorageType, Zone } from '../upload/upload.models';

export type { Category, StorageType, Zone } from '../upload/upload.models';

/** Who sees the statistics (x-roles: [DISPATCH, ADMIN] on every /stats/* path). */
export const STATS_ROLES: readonly Role[] = ['DISPATCH', 'ADMIN'];

export function canViewStats(role: Role | null | undefined): boolean {
  return !!role && STATS_ROLES.includes(role);
}

export const ALL_STORAGES: readonly StorageType[] = ['FTP', 'S3', 'DB'];

// ---------- GET /stats/storage-advisor ----------

/** One cell of the 4 x 3 matrix (schema StorageScore). */
export interface StorageScore {
  category: Category;
  storage: StorageType;
  /** 0-100: combines latency, errors and relative cost. */
  score: number;
  latencyP50Ms: number;
  latencyP95Ms: number;
  errorRatePercent: number;
  relativeCostPerGb: number;
  samples: number;
  /** true if the cell has benchmark data. */
  benchmarked: boolean;
}

export interface StorageRecommendation {
  category: Category;
  recommendedStorage: StorageType;
  currentPolicyStorage: StorageType;
  matchesPolicy: boolean;
  rationale: string;
}

export interface StorageAdvisor {
  generatedAt: string;
  basis: {
    benchmarkRunAt?: string | null;
    benchmarkSamples?: number;
    liveSamples?: number;
  };
  /** Relative cost per GB assumption from the YAML: `unit` (text) and one number per storage. */
  costAssumption: Record<string, unknown>;
  /** Exactly 12 cells (4 categories x 3 storage backends). */
  matrix: StorageScore[];
  recommendations: StorageRecommendation[];
  interpretation: string;
}

// ---------- GET /stats/peak-vs-normal ----------

export interface ScenarioWindow {
  scenario: string;
  from: string;
  to: string;
}

export interface ScenarioStats {
  requests: number;
  throughputPerMinute: number;
  latencyP50Ms: number;
  latencyP95Ms: number;
  latencyP99Ms: number;
  errorRatePercent: number;
  /** Requests rejected by the bulkhead. */
  rejected: number;
}

export interface CategoryComparison {
  category: Category;
  normal: ScenarioStats;
  peak: ScenarioStats;
  p95ChangePercent: number;
}

export interface PeakVsNormal {
  generatedAt: string;
  normal: ScenarioWindow;
  peak: ScenarioWindow;
  categories: CategoryComparison[];
  interpretation: string;
}

/** Peak scenarios from X-Scenario (contract, item 11). Default: storm. */
export const PEAK_SCENARIOS: readonly { value: string; label: string }[] = [
  { value: 'storm', label: 'Furtună (storm)' },
  { value: 'peak', label: 'Vârf de consum (peak)' },
];

// ---------- GET /stats/anomalies ----------

export interface MeterAnomaly {
  meterId: string;
  zone: Zone;
  readingTs: string;
  kwh: number;
  median: number;
  stddev: number;
  /** median + 3 x standard deviation. */
  threshold: number;
  /** How many standard deviations above the median. */
  deviations: number;
}

export interface SilentMeter {
  meterId: string;
  zone: Zone;
  lastReadingTs: string;
  silentMinutes: number;
}

export interface ZoneHourReadings {
  zone: Zone;
  /** Start of the hour, UTC. */
  hour: string;
  readings: number;
  totalKwh: number;
}

export interface Anomalies {
  generatedAt: string;
  from: string;
  to: string;
  rule: string;
  anomalies: {
    items: MeterAnomaly[];
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  };
  silentMeters: SilentMeter[];
  readingsByZoneHour: ZoneHourReadings[];
  interpretation: string;
}

export interface AnomaliesQuery {
  silentMinutes?: number;
  page?: number;
  size?: number;
}

// ---------- GET /stats/outage-zones ----------

export interface ZoneRank {
  rank: number;
  zone: Zone;
  outages: number;
  /** null when the whole interval has no outages (the percentage is undefined). 0 = a zone without outages in an interval with outages. */
  sharePercent: number | null;
  /** Ratio to the per-zone average; null when the whole interval has no outages. */
  vsAverageRatio: number | null;
  /** Hour (UTC) with the most outages; null when the zone had no outages. */
  peakHour: number | null;
}

export interface ZoneHourOutages {
  zone: Zone;
  hour: number;
  outages: number;
}

export interface OutageZones {
  generatedAt: string;
  from: string;
  to: string;
  totalOutages: number;
  ranking: ZoneRank[];
  byHour: ZoneHourOutages[];
  interpretation: string;
}

// ---------- GET /stats/requirements ----------

export type RequirementStatus = 'OK' | 'AT_RISK';

export interface Requirement {
  id: 'R1' | 'R2' | 'R3' | 'R4';
  category: Category;
  title: string;
  /** OK = green tile, AT_RISK = red. */
  status: RequirementStatus;
  message: string;
  thresholds: Record<string, unknown>;
  current: Record<string, unknown>;
}

export interface Requirements {
  generatedAt: string;
  requirements: Requirement[];
  /**
   * The contract's Requirements schema does NOT have this field (yet), although every response should carry an
   * interpretation. When missing, the UI composes the sentence from the tiles (requirementsInterpretation).
   */
  interpretation?: string;
}
