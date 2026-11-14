// Tipurile din contractul API pentru statistici (docs/api/openapi.yaml: StorageAdvisor, StorageScore,
// PeakVsNormal, ScenarioWindow, ScenarioStats, Anomalies, OutageZones, Requirements).
// Fiecare răspuns are o propoziție în română pentru oameni care iau decizii, în câmpul `interpretation`.

import { Role } from '../auth/auth.models';
import { Category, StorageType, Zone } from '../upload/upload.models';

export type { Category, StorageType, Zone } from '../upload/upload.models';

/** Cine vede statisticile (x-roles: [DISPATCH, ADMIN] pe toate căile /stats/*). */
export const STATS_ROLES: readonly Role[] = ['DISPATCH', 'ADMIN'];

export function canViewStats(role: Role | null | undefined): boolean {
  return !!role && STATS_ROLES.includes(role);
}

export const ALL_STORAGES: readonly StorageType[] = ['FTP', 'S3', 'DB'];

// ---------- GET /stats/storage-advisor ----------

/** O celulă din matricea 4 × 3 (schema StorageScore). */
export interface StorageScore {
  category: Category;
  storage: StorageType;
  /** 0–100: combină latența, erorile și costul relativ. */
  score: number;
  latencyP50Ms: number;
  latencyP95Ms: number;
  errorRatePercent: number;
  relativeCostPerGb: number;
  samples: number;
  /** true dacă celula are date din benchmark. */
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
  /** Presupunerea de cost relativ per GB, din YAML: `unit` (text) și câte un număr per storage. */
  costAssumption: Record<string, unknown>;
  /** Exact 12 celule (4 categorii × 3 storage-uri). */
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
  /** Cereri respinse de bulkhead. */
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

/** Scenariile de vârf din X-Scenario (contract, punctul 11). Implicit: storm. */
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
  /** mediană + 3 × abatere standard. */
  threshold: number;
  /** Câte abateri standard peste mediană. */
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
  /** Începutul orei, UTC. */
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
  /** null când tot intervalul n-are avarii (procentul nu e definit). 0 = zonă fără avarii într-un interval cu avarii. */
  sharePercent: number | null;
  /** Raport față de media pe zone; null când tot intervalul n-are avarii. */
  vsAverageRatio: number | null;
  /** Ora (UTC) cu cele mai multe avarii; null când zona n-are avarii. */
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
  /** OK = plăcuță verde, AT_RISK = roșie. */
  status: RequirementStatus;
  message: string;
  thresholds: Record<string, unknown>;
  current: Record<string, unknown>;
}

export interface Requirements {
  generatedAt: string;
  requirements: Requirement[];
  /**
   * Schema Requirements din contract NU are (încă) acest câmp, deși planul cere interpretare pe fiecare răspuns.
   * Dacă lipsește, interfața compune propoziția din plăcuțe (requirementsInterpretation).
   */
  interpretation?: string;
}
