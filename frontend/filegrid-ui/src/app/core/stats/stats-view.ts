// Pure transformations of /stats/* responses into what the dashboard cards display.
// No Angular dependency, so they are simple to test (stats-view.spec.ts).

import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from '../api/api-error';
import { ALL_CATEGORIES, CATEGORY_LABELS } from '../files/files.models';
import {
  ALL_STORAGES,
  Category,
  CategoryComparison,
  MeterAnomaly,
  OutageZones,
  Requirement,
  Requirements,
  SilentMeter,
  StorageAdvisor,
  StorageRecommendation,
  StorageScore,
  StorageType,
  ZoneHourOutages,
  ZoneRank,
} from './stats.models';

// ---------- 1. Storage recommendation ----------

export interface AdvisorCell {
  storage: StorageType;
  /** null only if the server did not send the cell (the contract always requires 12). */
  score: StorageScore | null;
  recommended: boolean;
  /** The storage the YAML policy currently uses for this category. */
  currentPolicy: boolean;
}

export interface AdvisorRow {
  category: Category;
  label: string;
  cells: AdvisorCell[];
  recommendation: StorageRecommendation | null;
  /** Highlighted storage: the server's recommendation or, if missing, the one with the highest score. */
  recommendedStorage: StorageType | null;
}

export interface AdvisorMatrix {
  rows: AdvisorRow[];
  /** How many of the 12 cells are missing from the response (should be 0). */
  missingCells: number;
}

/** Puts the matrix in a fixed order (4 categories x FTP/S3/DB), regardless of the response order. */
export function buildAdvisorMatrix(advisor: StorageAdvisor): AdvisorMatrix {
  let missingCells = 0;
  const rows = ALL_CATEGORIES.map((category): AdvisorRow => {
    const scores = ALL_STORAGES.map((s) => advisor.matrix.find((c) => c.category === category && c.storage === s) ?? null);
    missingCells += scores.filter((s) => s === null).length;
    const recommendation = advisor.recommendations.find((r) => r.category === category) ?? null;
    const recommendedStorage = recommendation?.recommendedStorage ?? bestStorage(scores);
    return {
      category,
      label: CATEGORY_LABELS[category],
      recommendation,
      recommendedStorage,
      cells: ALL_STORAGES.map((storage, i) => ({
        storage,
        score: scores[i],
        recommended: storage === recommendedStorage,
        currentPolicy: storage === recommendation?.currentPolicyStorage,
      })),
    };
  });
  return { rows, missingCells };
}

function bestStorage(scores: (StorageScore | null)[]): StorageType | null {
  let best: StorageScore | null = null;
  for (const s of scores) {
    if (s && (!best || s.score > best.score)) {
      best = s;
    }
  }
  return best?.storage ?? null;
}

/** Cell colour intensity (0-100 -> 8-45%), so high scores stand out. */
export function scoreTint(score: number): number {
  const clamped = Math.max(0, Math.min(100, score));
  return Math.round(8 + (clamped / 100) * 37);
}

export interface CostLine {
  unit: string;
  values: { storage: StorageType; cost: number }[];
}

/** Cost assumption from the YAML, in FTP, S3, DB order (numeric values only). */
export function costAssumptionLine(cost: Record<string, unknown>): CostLine {
  return {
    unit: typeof cost['unit'] === 'string' ? cost['unit'] : 'cost relativ per GB',
    values: ALL_STORAGES.filter((s) => typeof cost[s] === 'number').map((s) => ({ storage: s, cost: cost[s] as number })),
  };
}

// ---------- 2. Normal day vs. peak ----------

export type ChangeLevel = 'stable' | 'moderate' | 'high';

/** Below 10% = stable, below 50% = moderate, otherwise high (for the p95 percentage colour). */
export function changeLevel(percent: number): ChangeLevel {
  const abs = Math.abs(percent);
  return abs < 10 ? 'stable' : abs < 50 ? 'moderate' : 'high';
}

export interface ComparisonRow {
  category: Category;
  label: string;
  item: CategoryComparison;
  /** p95 bar widths (percent), scaled per row: latencies differ enormously between categories. */
  normalBar: number;
  peakBar: number;
  /** How many times requests grew (null if the normal day had no requests). */
  requestsRatio: number | null;
  level: ChangeLevel;
}

/** Comparison rows, in the fixed category order. */
export function comparisonRows(categories: CategoryComparison[]): ComparisonRow[] {
  return ALL_CATEGORIES.flatMap((category) => {
    const item = categories.find((c) => c.category === category);
    if (!item) {
      return [];
    }
    const max = Math.max(item.normal.latencyP95Ms, item.peak.latencyP95Ms, 1);
    return [
      {
        category,
        label: CATEGORY_LABELS[category],
        item,
        normalBar: (item.normal.latencyP95Ms / max) * 100,
        peakBar: (item.peak.latencyP95Ms / max) * 100,
        requestsRatio: item.normal.requests > 0 ? item.peak.requests / item.normal.requests : null,
        level: changeLevel(item.p95ChangePercent),
      },
    ];
  });
}

// ---------- 3. Anomalies ----------

/** Worst anomalies first (by how many standard deviations they exceed the median). */
export function sortAnomalies(items: MeterAnomaly[]): MeterAnomaly[] {
  return [...items].sort((a, b) => b.deviations - a.deviations);
}

/** Meters silent for the longest time first. */
export function sortSilentMeters(items: SilentMeter[]): SilentMeter[] {
  return [...items].sort((a, b) => b.silentMinutes - a.silentMinutes);
}

/** "155 min" or "2 h 35 min". */
export function formatMinutes(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

// ---------- 4. Outage zones ----------

export interface ZoneBar {
  zone: ZoneRank;
  /** Bar width, as a percentage of the zone with the most outages. */
  width: number;
  /** Outages over the 24 hours (UTC), for the hourly strip. */
  hours: number[];
}

/** Descending ranking by outages (we do not rely on the response order) and its bars. */
export function zoneBars(ranking: ZoneRank[], byHour: ZoneHourOutages[]): ZoneBar[] {
  const sorted = [...ranking].sort((a, b) => b.outages - a.outages || a.rank - b.rank);
  const max = Math.max(1, ...sorted.map((z) => z.outages));
  return sorted.map((zone) => {
    const hours = new Array<number>(24).fill(0);
    for (const h of byHour) {
      if (h.zone === zone.zone && h.hour >= 0 && h.hour < 24) {
        hours[h.hour] += h.outages;
      }
    }
    return { zone, width: (zone.outages / max) * 100, hours };
  });
}

/** "09:00". */
export function formatHour(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

/** What we display for a value the server cannot compute (null), so it does not look like 0. */
export const NO_VALUE = '—';

/** "09:00", or a dash when the zone had no outages (peakHour null). */
export function formatPeakHour(hour: number | null): string {
  return hour === null ? NO_VALUE : formatHour(hour);
}

/** "54,9%", "0%" (a real 0) or a dash (null: undefined). */
export function formatShare(percent: number | null): string {
  return percent === null ? NO_VALUE : `${percent.toLocaleString('ro-RO', { maximumFractionDigits: 1 })}%`;
}

/** "x1,65", "x0,00" (a real 0) or a dash (null: undefined). */
export function formatRatio(ratio: number | null): string {
  return ratio === null
    ? NO_VALUE
    : `×${ratio.toLocaleString('ro-RO', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/**
 * Does the outage statistic make sense for the chosen interval? Not if the interval has no outages at all:
 * total 0, empty ranking, or every computed field null (O7 then sends null instead of percentages).
 */
export function hasOutageData(res: OutageZones): boolean {
  return (
    res.totalOutages > 0 &&
    res.ranking.length > 0 &&
    !res.ranking.every((z) => z.sharePercent === null && z.vsAverageRatio === null && z.peakHour === null)
  );
}

// ---------- 5. Business requirements ----------

export type Tone = 'green' | 'red' | 'neutral';

/** true only when the server explicitly says `current.sufficientData: false` (a missing field = enough data). */
export function hasInsufficientData(req: { current?: Record<string, unknown> | null }): boolean {
  return req.current?.['sufficientData'] === false;
}

/**
 * AT_RISK or any unknown value -> red (a false alarm beats a missed one), even with little data.
 * OK with `current.sufficientData === false` -> neutral (grey): too few measurements for a verdict. Otherwise OK -> green.
 */
export function requirementTone(req: Pick<Requirement, 'status'> & { current?: Record<string, unknown> | null }): Tone {
  if (req.status !== 'OK') {
    return 'red';
  }
  return hasInsufficientData(req) ? 'neutral' : 'green';
}

/** Tile label, by tone. */
export const TONE_LABELS: Readonly<Record<Tone, string>> = {
  green: 'Îndeplinită',
  red: 'În pericol',
  neutral: 'Date insuficiente',
};

/** Tiles in R1-R4 order. */
export function sortRequirements(items: Requirement[]): Requirement[] {
  return [...items].sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * The interpretation sentence: the server's if present; otherwise composed from the tiles
 * (the contract's Requirements schema has no `interpretation` field).
 */
export function requirementsInterpretation(res: Requirements): string {
  if (res.interpretation?.trim()) {
    return res.interpretation;
  }
  const sorted = sortRequirements(res.requirements);
  const atRisk = sorted.filter((r) => requirementTone(r) === 'red');
  const unknown = sorted.filter((r) => requirementTone(r) === 'neutral');
  const total = res.requirements.length;
  if (atRisk.length === 0 && unknown.length === 0) {
    return `Toate cele ${total} cerințe de business sunt îndeplinite acum; nu e nevoie de nicio intervenție.`;
  }
  const ok = total - atRisk.length - unknown.length;
  const detail = (r: Requirement) => `${r.id} (${r.title.charAt(0).toLowerCase()}${r.title.slice(1)}): ${r.message}`;
  const parts = [`${ok} din ${total} cerințe sunt îndeplinite`];
  if (atRisk.length) {
    parts.push(atRisk.length === 1 ? 'una e în pericol' : `${atRisk.length} sunt în pericol`);
  }
  if (unknown.length) {
    // Without enough measurements we say neither "met" nor "at risk".
    parts.push(
      unknown.length === 1
        ? 'una nu are încă date suficiente pentru un verdict'
        : `${unknown.length} nu au încă date suficiente pentru un verdict`,
    );
  }
  const sentences = [`${parts.join('; ')}.`, ...atRisk.map(detail), ...unknown.map(detail)];
  sentences.push(
    atRisk.length
      ? 'Recomandăm verificarea acum, înainte să devină incident.'
      : 'Nicio cerință nu e în pericol; verdictul complet apare după ce sistemul strânge destule măsurători.',
  );
  return sentences.join(' ');
}

// ---------- errors ----------

/**
 * 503 with `details.reason` from O7: the performance statistic cannot be computed yet because measurements
 * (C12) are missing. Not a failure, so the card shows an informative message rather than an error.
 */
export const UNAVAILABLE_REASONS: Readonly<Record<string, string>> = {
  REQUEST_METRICS_UNAVAILABLE:
    'Statistica nu este încă disponibilă: lipsesc măsurătorile de performanță. Va apărea automat după ce sistemul strânge date.',
  STORAGE_MEASUREMENTS_INCOMPLETE:
    'Recomandarea nu este încă disponibilă: lipsesc măsurătorile de performanță pentru unele combinații categorie–stocare. ' +
    'Va apărea automat după ce sistemul strânge date.',
};

export interface StatsFailure {
  /** unavailable = data not there yet (informative); error = something is broken. */
  kind: 'unavailable' | 'error';
  message: string;
}

/** Like describeStatsError, but separates "no data yet" (503 with a known reason) from real errors. */
export function describeStatsFailure(error: unknown): StatsFailure {
  const api = toApiError(error);
  const status = api?.status ?? (error instanceof HttpErrorResponse ? error.status : -1);
  const reason = api?.details?.['reason'];
  if (status === 503 && typeof reason === 'string' && reason in UNAVAILABLE_REASONS) {
    return { kind: 'unavailable', message: UNAVAILABLE_REASONS[reason] };
  }
  return { kind: 'error', message: describeStatsError(error) };
}

/** Romanian user-facing message for /stats/* errors, in the style of file-errors.ts. */
export function describeStatsError(error: unknown): string {
  const api = toApiError(error);
  const status = api?.status ?? (error instanceof HttpErrorResponse ? error.status : -1);
  const trace = api?.traceId ? ` (cod de urmărire ${api.traceId})` : '';
  switch (status) {
    case 0:
      return 'Serverul nu răspunde (rețea căzută sau API oprit). Încearcă din nou.';
    case 401:
      return 'Sesiunea a expirat. Autentifică-te din nou.';
    case 403:
      return `Rolul tău nu are acces la statistici (doar Dispecerat și Administrator).${trace}`;
    case 404:
    case 405:
    case 501:
      // The endpoint is not implemented on the server (yet).
      return 'Funcția aceasta nu este încă disponibilă pe server.';
    case 429:
    case 503:
      return `Statisticile nu se pot calcula acum (serviciu ocupat sau bază de date indisponibilă); reîncearcă${api?.retryAfterSeconds ? ` peste ${api.retryAfterSeconds} s` : ''}.${trace}`;
    default:
      return api?.message ? `${api.message}${trace}` : `Eroare neașteptată (${status}).`;
  }
}
