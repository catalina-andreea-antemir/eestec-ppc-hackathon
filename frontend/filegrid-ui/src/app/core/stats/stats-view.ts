// Transformări pure ale răspunsurilor /stats/* în ce afișează cărțile din dashboard.
// Fără Angular, ca să se poată testa simplu (stats-view.spec.ts).

import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from '../api/api-error';
import { ALL_CATEGORIES, CATEGORY_LABELS } from '../files/files.models';
import {
  ALL_STORAGES,
  Category,
  CategoryComparison,
  MeterAnomaly,
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

// ---------- 1. Recomandarea de storage ----------

export interface AdvisorCell {
  storage: StorageType;
  /** null doar dacă serverul n-a trimis celula (contractul cere mereu 12). */
  score: StorageScore | null;
  recommended: boolean;
  /** Storage-ul folosit acum de politica YAML pentru categoria asta. */
  currentPolicy: boolean;
}

export interface AdvisorRow {
  category: Category;
  label: string;
  cells: AdvisorCell[];
  recommendation: StorageRecommendation | null;
  /** Storage-ul evidențiat: din recomandarea serverului sau, dacă lipsește, cel cu scorul cel mai mare. */
  recommendedStorage: StorageType | null;
}

export interface AdvisorMatrix {
  rows: AdvisorRow[];
  /** Câte dintre cele 12 celule lipsesc din răspuns (ar trebui 0). */
  missingCells: number;
}

/** Pune matricea în ordine fixă (4 categorii × FTP/S3/DB), indiferent de ordinea din răspuns. */
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

/** Intensitatea culorii celulei (0–100 → 8–45%), ca scorurile mari să iasă în evidență. */
export function scoreTint(score: number): number {
  const clamped = Math.max(0, Math.min(100, score));
  return Math.round(8 + (clamped / 100) * 37);
}

export interface CostLine {
  unit: string;
  values: { storage: StorageType; cost: number }[];
}

/** Ipoteza de cost din YAML, în ordinea FTP, S3, DB (doar valorile numerice). */
export function costAssumptionLine(cost: Record<string, unknown>): CostLine {
  return {
    unit: typeof cost['unit'] === 'string' ? cost['unit'] : 'cost relativ per GB',
    values: ALL_STORAGES.filter((s) => typeof cost[s] === 'number').map((s) => ({ storage: s, cost: cost[s] as number })),
  };
}

// ---------- 2. Zi normală vs. vârf ----------

export type ChangeLevel = 'stable' | 'moderate' | 'high';

/** Sub 10% = stabil, sub 50% = moderat, altfel ridicat (pentru culoarea procentului p95). */
export function changeLevel(percent: number): ChangeLevel {
  const abs = Math.abs(percent);
  return abs < 10 ? 'stable' : abs < 50 ? 'moderate' : 'high';
}

export interface ComparisonRow {
  category: Category;
  label: string;
  item: CategoryComparison;
  /** Lățimile barelor p95 (procente), scalate pe rând: latențele diferă enorm între categorii. */
  normalBar: number;
  peakBar: number;
  /** De câte ori au crescut cererile (null dacă ziua normală n-a avut cereri). */
  requestsRatio: number | null;
  level: ChangeLevel;
}

/** Rândurile comparației, în ordinea fixă a categoriilor. */
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

// ---------- 3. Anomalii ----------

/** Anomaliile cele mai grave primele (după câte abateri standard depășesc mediana). */
export function sortAnomalies(items: MeterAnomaly[]): MeterAnomaly[] {
  return [...items].sort((a, b) => b.deviations - a.deviations);
}

/** Contoarele tăcute de cel mai mult timp primele. */
export function sortSilentMeters(items: SilentMeter[]): SilentMeter[] {
  return [...items].sort((a, b) => b.silentMinutes - a.silentMinutes);
}

/** „155 min” sau „2 h 35 min”. */
export function formatMinutes(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

// ---------- 4. Zone de avarii ----------

export interface ZoneBar {
  zone: ZoneRank;
  /** Lățimea barei, procent din zona cu cele mai multe avarii. */
  width: number;
  /** Avariile pe cele 24 de ore (UTC), pentru banda orară. */
  hours: number[];
}

/** Clasamentul descrescător după avarii (nu ne bazăm pe ordinea din răspuns) și barele lui. */
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

/** „09:00”. */
export function formatHour(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

// ---------- 5. Cerințele de business ----------

export type Tone = 'green' | 'red';

/** OK → verde; AT_RISK sau orice valoare necunoscută → roșu (mai bine o alarmă falsă decât una ratată). */
export function requirementTone(req: Pick<Requirement, 'status'>): Tone {
  return req.status === 'OK' ? 'green' : 'red';
}

/** Plăcuțele în ordinea R1–R4. */
export function sortRequirements(items: Requirement[]): Requirement[] {
  return [...items].sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * Propoziția de interpretare: cea de la server dacă există; altfel compusă din plăcuțe
 * (schema Requirements din contract nu are câmpul `interpretation`).
 */
export function requirementsInterpretation(res: Requirements): string {
  if (res.interpretation?.trim()) {
    return res.interpretation;
  }
  const atRisk = sortRequirements(res.requirements).filter((r) => requirementTone(r) === 'red');
  const total = res.requirements.length;
  if (atRisk.length === 0) {
    return `Toate cele ${total} cerințe de business sunt îndeplinite acum; nu e nevoie de nicio intervenție.`;
  }
  const list = atRisk.map((r) => `${r.id} (${r.title.charAt(0).toLowerCase()}${r.title.slice(1)}): ${r.message}`).join(' ');
  const ok = total - atRisk.length;
  const head =
    atRisk.length === 1
      ? `${ok} din ${total} cerințe sunt îndeplinite; una e în pericol.`
      : `${ok} din ${total} cerințe sunt îndeplinite; ${atRisk.length} sunt în pericol.`;
  return `${head} ${list} Recomandăm verificarea acum, înainte să devină incident.`;
}

// ---------- erori ----------

/** Mesaj în română pentru erorile de la /stats/*, în stilul din file-errors.ts. */
export function describeStatsError(error: unknown): string {
  const api = toApiError(error);
  const status = api?.status ?? (error instanceof HttpErrorResponse ? error.status : -1);
  const trace = api?.traceId ? ` (trace ${api.traceId})` : '';
  switch (status) {
    case 0:
      return 'Serverul nu răspunde (rețea căzută sau API oprit). Încearcă din nou.';
    case 401:
      return 'Sesiunea a expirat. Autentifică-te din nou.';
    case 403:
      return `Rolul tău nu are acces la statistici (doar DISPATCH și ADMIN).${trace}`;
    case 404:
    case 405:
    case 501:
      // Endpoint-ul nu e (încă) implementat pe server.
      return 'Funcția aceasta nu este încă disponibilă pe server.';
    case 429:
    case 503:
      return `Statisticile nu se pot calcula acum (serviciu ocupat sau bază de date indisponibilă); reîncearcă${api?.retryAfterSeconds ? ` peste ${api.retryAfterSeconds} s` : ''}.${trace}`;
    default:
      return api?.message ? `${api.message}${trace}` : `Eroare neașteptată (${status}).`;
  }
}
