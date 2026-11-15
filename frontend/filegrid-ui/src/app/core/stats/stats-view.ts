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
  OutageZones,
  Requirement,
  Requirements,
  SilentMeter,
  StorageAdvisor,
  StorageScore,
  StorageType,
  ZoneHourOutages,
  ZoneRank,
} from './stats.models';

// ---------- 1. Unde se stochează fiecare categorie ----------
// Decizia echipei pentru demo: pe fiecare categorie apare DOAR storage-ul ales de politică (currentPolicyStorage),
// cu cifrele măsurate în benchmark pentru acel storage. Fără comparații între storage-uri și fără sugestii.
// Câmpurile recommendedStorage / matchesPolicy / rationale și interpretarea serverului se ignoră.

export interface PolicyRow {
  category: Category;
  label: string;
  /** Storage-ul din politică; null dacă serverul n-a trimis categoria. */
  storage: StorageType | null;
  /** Cifrele din benchmark pentru exact acest storage; null dacă lipsesc. */
  measured: StorageScore | null;
}

/** Cele 4 categorii în ordine fixă, fiecare cu storage-ul din politică și măsurătorile lui. */
export function policyRows(advisor: StorageAdvisor): PolicyRow[] {
  return ALL_CATEGORIES.map((category) => {
    const storage = advisor.recommendations.find((r) => r.category === category)?.currentPolicyStorage ?? null;
    const measured = storage ? (advisor.matrix.find((c) => c.category === category && c.storage === storage) ?? null) : null;
    return { category, label: CATEGORY_LABELS[category], storage, measured };
  });
}

/** Propoziția neutră a cărții: „Politica actuală: Citiri contoare → Bază de date, …; cifrele sunt din benchmark…”. */
export function policyInterpretation(rows: PolicyRow[], storageLabels: Record<StorageType, string>): string | null {
  const known = rows.filter((r) => r.storage !== null);
  if (known.length === 0) {
    return null;
  }
  const list = known.map((r) => `${r.label} → ${storageLabels[r.storage!]}`).join(', ');
  return `Politica actuală: ${list}; cifrele sunt din benchmark-ul pe cele 12 combinații categorie × stocare.`;
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

/** Ce afișăm pentru o valoare pe care serverul n-o poate calcula (null), ca să nu pară 0. */
export const NO_VALUE = '—';

/** „09:00”, sau „—” când zona n-a avut avarii (peakHour null). */
export function formatPeakHour(hour: number | null): string {
  return hour === null ? NO_VALUE : formatHour(hour);
}

/** „54,9%”, „0%” (0 real) sau „—” (null: nedefinit). */
export function formatShare(percent: number | null): string {
  return percent === null ? NO_VALUE : `${percent.toLocaleString('ro-RO', { maximumFractionDigits: 1 })}%`;
}

/** „×1,65”, „×0,00” (0 real) sau „—” (null: nedefinit). */
export function formatRatio(ratio: number | null): string {
  return ratio === null
    ? NO_VALUE
    : `×${ratio.toLocaleString('ro-RO', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/**
 * Are statistica de avarii sens pentru intervalul ales? Nu, dacă intervalul n-are nicio avarie: total 0,
 * clasament gol sau toate câmpurile calculate null (O7 trimite atunci null în loc de procente).
 */
export function hasOutageData(res: OutageZones): boolean {
  return (
    res.totalOutages > 0 &&
    res.ranking.length > 0 &&
    !res.ranking.every((z) => z.sharePercent === null && z.vsAverageRatio === null && z.peakHour === null)
  );
}

// ---------- 5. Cerințele de business ----------

export type Tone = 'green' | 'red' | 'neutral';

/** true doar când serverul spune explicit `current.sufficientData: false` (lipsa câmpului = date suficiente). */
export function hasInsufficientData(req: { current?: Record<string, unknown> | null }): boolean {
  return req.current?.['sufficientData'] === false;
}

/**
 * AT_RISK sau orice valoare necunoscută → roșu (mai bine o alarmă falsă decât una ratată), chiar dacă datele sunt puține.
 * OK cu `current.sufficientData === false` → neutru (gri): prea puține măsurători pentru un verdict. Altfel OK → verde.
 */
export function requirementTone(req: Pick<Requirement, 'status'> & { current?: Record<string, unknown> | null }): Tone {
  if (req.status !== 'OK') {
    return 'red';
  }
  return hasInsufficientData(req) ? 'neutral' : 'green';
}

/** Eticheta de pe plăcuță, după ton. */
export const TONE_LABELS: Readonly<Record<Tone, string>> = {
  green: 'Îndeplinită',
  red: 'În pericol',
  neutral: 'Date insuficiente',
};

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
    // Fără destule măsurători nu spunem nici „îndeplinită”, nici „în pericol”.
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

// ---------- erori ----------

/**
 * 503 cu `details.reason` de la O7: statistica de performanță nu se poate calcula încă, pentru că lipsesc
 * măsurătorile (C12). Nu e o defecțiune, deci cartea arată un mesaj informativ, nu o eroare.
 */
export const UNAVAILABLE_REASONS: Readonly<Record<string, string>> = {
  REQUEST_METRICS_UNAVAILABLE:
    'Statistica nu este încă disponibilă: lipsesc măsurătorile de performanță. Va apărea automat după ce sistemul strânge date.',
  STORAGE_MEASUREMENTS_INCOMPLETE:
    'Recomandarea nu este încă disponibilă: lipsesc măsurătorile de performanță pentru unele combinații categorie–stocare. ' +
    'Va apărea automat după ce sistemul strânge date.',
};

export interface StatsFailure {
  /** unavailable = lipsesc încă datele (informativ); error = ceva nu merge. */
  kind: 'unavailable' | 'error';
  message: string;
}

/** Ca describeStatsError, dar separă „încă nu sunt date” (503 cu reason cunoscut) de erorile adevărate. */
export function describeStatsFailure(error: unknown): StatsFailure {
  const api = toApiError(error);
  const status = api?.status ?? (error instanceof HttpErrorResponse ? error.status : -1);
  const reason = api?.details?.['reason'];
  if (status === 503 && typeof reason === 'string' && reason in UNAVAILABLE_REASONS) {
    return { kind: 'unavailable', message: UNAVAILABLE_REASONS[reason] };
  }
  return { kind: 'error', message: describeStatsError(error) };
}

/** Mesaj în română pentru erorile de la /stats/*, în stilul din file-errors.ts. */
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
      // Endpoint-ul nu e (încă) implementat pe server.
      return 'Funcția aceasta nu este încă disponibilă pe server.';
    case 429:
    case 503:
      return `Statisticile nu se pot calcula acum (serviciu ocupat sau bază de date indisponibilă); reîncearcă${api?.retryAfterSeconds ? ` peste ${api.retryAfterSeconds} s` : ''}.${trace}`;
    default:
      return api?.message ? `${api.message}${trace}` : `Eroare neașteptată (${status}).`;
  }
}
