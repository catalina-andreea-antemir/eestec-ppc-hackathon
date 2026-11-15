import { HttpEvent, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  Anomalies,
  canViewStats,
  CategoryComparison,
  OutageZones,
  PeakVsNormal,
  Requirements,
  StorageAdvisor,
  ZoneHourOutages,
} from '../stats/stats.models';
import { apiError, MockUser, nowIso, respond } from './mock-uploads';

/**
 * Mock pentru dashboard (GET /stats/*), cu exemplele din contract (docs/api/openapi.yaml).
 *
 * - Doar DISPATCH și ADMIN primesc date (x-roles); celelalte roluri primesc 403 FORBIDDEN.
 * - /stats/peak-vs-normal: `peakScenario=storm` (implicit) e exemplul din contract (furtună: avariile cresc
 *   de 13 ori); `peakScenario=peak` e o zi de vârf de consum (citirile se dublează). Alt scenariu → 0 cereri.
 * - /stats/outage-zones: clasamentul din contract, cu distribuția pe ore completată (sumele se potrivesc).
 * - /stats/requirements: exemplul din contract (R2 roșu), cu R4 ca în C12 când sunt prea puține transferuri
 *   (`current.sufficientData: false` → plăcuță gri). Schema nu are `interpretation`, deci nici mock-ul.
 * - Situațiile din O7 se simulează din selectorul „Simulare (mock)” al paginii Statistici (MockStatsMode).
 */
export function handleStatsRoutes(
  req: HttpRequest<unknown>,
  path: string,
  user: MockUser,
  mode: MockStatsMode = getMockStatsMode(),
): Observable<HttpEvent<unknown>> | null {
  if (!path.startsWith('/stats/')) {
    return null;
  }
  if (req.method !== 'GET') {
    return apiError(405, 'METHOD_NOT_ALLOWED', `Metoda ${req.method} nu e permisă pe ${path}.`);
  }
  if (!canViewStats(user.role)) {
    return apiError(403, 'FORBIDDEN', `Rolul ${user.role} nu are acces la statistici.`);
  }
  if (mode === 'metrics-missing' && path in MISSING_METRICS) {
    // Ca O7 cât timp lipsesc măsurătorile C12: 503 STORAGE_UNAVAILABLE cu details.reason și Retry-After.
    return apiError(503, 'STORAGE_UNAVAILABLE', 'Măsurătorile de performanță nu sunt încă disponibile.', {
      retryAfterSeconds: 60,
      details: MISSING_METRICS[path],
    });
  }
  switch (path) {
    case '/stats/storage-advisor':
      return respond(200, { ...STORAGE_ADVISOR, generatedAt: nowIso() });
    case '/stats/peak-vs-normal':
      return respond(200, peakVsNormal(req.params.get('normalScenario') ?? 'normal', req.params.get('peakScenario') ?? 'storm'));
    case '/stats/anomalies':
      return anomalies(req);
    case '/stats/outage-zones':
      return respond(200, { ...outageZones(mode), generatedAt: nowIso() });
    case '/stats/requirements':
      return respond(200, { ...REQUIREMENTS, generatedAt: nowIso() });
    default:
      return apiError(404, 'NOT_FOUND', `Endpoint-ul GET ${path} nu există.`);
  }
}

const STORAGE_ADVISOR: StorageAdvisor = {
  generatedAt: '2026-11-14T13:05:00Z',
  basis: { benchmarkRunAt: '2026-11-14T11:40:12Z', benchmarkSamples: 231, liveSamples: 18432 },
  costAssumption: { unit: 'cost relativ per GB (FTP = 1,0)', FTP: 1.0, S3: 1.4, DB: 3.2 },
  matrix: [
    { category: 'METER_READING', storage: 'FTP', score: 58, latencyP50Ms: 38, latencyP95Ms: 96, errorRatePercent: 0.4, relativeCostPerGb: 1.0, samples: 200, benchmarked: true },
    { category: 'METER_READING', storage: 'S3', score: 71, latencyP50Ms: 21, latencyP95Ms: 54, errorRatePercent: 0.0, relativeCostPerGb: 1.4, samples: 200, benchmarked: true },
    { category: 'METER_READING', storage: 'DB', score: 84, latencyP50Ms: 9, latencyP95Ms: 23, errorRatePercent: 0.0, relativeCostPerGb: 3.2, samples: 18000, benchmarked: true },
    { category: 'OUTAGE_RECORD', storage: 'FTP', score: 64, latencyP50Ms: 410, latencyP95Ms: 880, errorRatePercent: 0.8, relativeCostPerGb: 1.0, samples: 10, benchmarked: true },
    { category: 'OUTAGE_RECORD', storage: 'S3', score: 88, latencyP50Ms: 190, latencyP95Ms: 340, errorRatePercent: 0.0, relativeCostPerGb: 1.4, samples: 112, benchmarked: true },
    { category: 'OUTAGE_RECORD', storage: 'DB', score: 70, latencyP50Ms: 520, latencyP95Ms: 1040, errorRatePercent: 0.0, relativeCostPerGb: 3.2, samples: 10, benchmarked: true },
    { category: 'CONNECTION_DOC', storage: 'FTP', score: 52, latencyP50Ms: 120, latencyP95Ms: 260, errorRatePercent: 0.5, relativeCostPerGb: 1.0, samples: 20, benchmarked: true },
    { category: 'CONNECTION_DOC', storage: 'S3', score: 74, latencyP50Ms: 70, latencyP95Ms: 150, errorRatePercent: 0.0, relativeCostPerGb: 1.4, samples: 20, benchmarked: true },
    { category: 'CONNECTION_DOC', storage: 'DB', score: 81, latencyP50Ms: 95, latencyP95Ms: 190, errorRatePercent: 0.0, relativeCostPerGb: 3.2, samples: 41, benchmarked: true },
    { category: 'INSPECTION_VIDEO', storage: 'FTP', score: 79, latencyP50Ms: 9800, latencyP95Ms: 9800, errorRatePercent: 0.0, relativeCostPerGb: 1.0, samples: 1, benchmarked: true },
    { category: 'INSPECTION_VIDEO', storage: 'S3', score: 86, latencyP50Ms: 7400, latencyP95Ms: 7400, errorRatePercent: 0.0, relativeCostPerGb: 1.4, samples: 1, benchmarked: true },
    { category: 'INSPECTION_VIDEO', storage: 'DB', score: 41, latencyP50Ms: 31200, latencyP95Ms: 31200, errorRatePercent: 0.0, relativeCostPerGb: 3.2, samples: 1, benchmarked: true },
  ],
  recommendations: [
    { category: 'METER_READING', recommendedStorage: 'DB', currentPolicyStorage: 'DB', matchesPolicy: true, rationale: 'Fișiere de 1–50 KB, foarte multe; DB are cea mai mică latență p95 (23 ms).' },
    { category: 'OUTAGE_RECORD', recommendedStorage: 'S3', currentPolicyStorage: 'S3', matchesPolicy: true, rationale: 'Cel mai bun raport latență/cost pentru fișiere de 1–10 MB.' },
    { category: 'CONNECTION_DOC', recommendedStorage: 'DB', currentPolicyStorage: 'DB', matchesPolicy: true, rationale: 'Scor apropiat de S3, dar datele personale rămân într-un singur loc, cu acces controlat.' },
    { category: 'INSPECTION_VIDEO', recommendedStorage: 'S3', currentPolicyStorage: 'FTP', matchesPolicy: false, rationale: 'S3 transferă un video de 100 MB cu 24% mai rapid decât FTP; costul e doar cu 40% mai mare.' },
  ],
  interpretation:
    'Politica actuală este optimă pentru 3 din 4 categorii. Pentru video-uri de dronă, S3 ar fi cu 24% mai rapid decât FTP ' +
    'la un cost cu 40% mai mare; recomandăm evaluarea schimbării în YAML, fără modificări de cod.',
};

const NORMAL_DAY: CategoryComparison[] = [
  { category: 'METER_READING', normal: { requests: 5400, throughputPerMinute: 180.0, latencyP50Ms: 9, latencyP95Ms: 23, latencyP99Ms: 41, errorRatePercent: 0.0, rejected: 0 }, peak: { requests: 0, throughputPerMinute: 0, latencyP50Ms: 0, latencyP95Ms: 0, latencyP99Ms: 0, errorRatePercent: 0, rejected: 0 }, p95ChangePercent: 0 },
  { category: 'OUTAGE_RECORD', normal: { requests: 36, throughputPerMinute: 1.2, latencyP50Ms: 190, latencyP95Ms: 340, latencyP99Ms: 410, errorRatePercent: 0.0, rejected: 0 }, peak: { requests: 0, throughputPerMinute: 0, latencyP50Ms: 0, latencyP95Ms: 0, latencyP99Ms: 0, errorRatePercent: 0, rejected: 0 }, p95ChangePercent: 0 },
  { category: 'CONNECTION_DOC', normal: { requests: 240, throughputPerMinute: 8.0, latencyP50Ms: 95, latencyP95Ms: 190, latencyP99Ms: 260, errorRatePercent: 0.0, rejected: 0 }, peak: { requests: 0, throughputPerMinute: 0, latencyP50Ms: 0, latencyP95Ms: 0, latencyP99Ms: 0, errorRatePercent: 0, rejected: 0 }, p95ChangePercent: 0 },
  { category: 'INSPECTION_VIDEO', normal: { requests: 3, throughputPerMinute: 0.1, latencyP50Ms: 9800, latencyP95Ms: 9800, latencyP99Ms: 9800, errorRatePercent: 0.0, rejected: 0 }, peak: { requests: 0, throughputPerMinute: 0, latencyP50Ms: 0, latencyP95Ms: 0, latencyP99Ms: 0, errorRatePercent: 0, rejected: 0 }, p95ChangePercent: 0 },
];

/** Ziua de vârf per scenariu: [cereri, throughput/min, p50, p95, p99, erori %, respinse] per categorie. */
const PEAK_DAYS: Record<string, { values: number[][]; interpretation: string }> = {
  storm: {
    values: [
      [5392, 179.7, 9, 24, 44, 0.0, 0],
      [480, 16.0, 260, 710, 1250, 0.6, 14],
      [238, 7.9, 96, 195, 270, 0.0, 0],
      [3, 0.1, 9900, 9900, 9900, 0.0, 0],
    ],
    interpretation:
      'În ziua de furtună avariile au crescut de 13 ori (de la 36 la 480 de cereri), iar latența lor p95 a crescut de la ' +
      '340 ms la 710 ms; celelalte categorii au rămas neschimbate (sub 5%). Izolarea pe categorii funcționează: 14 cereri ' +
      'de avarie au fost respinse, fără impact asupra citirilor sau documentelor.',
  },
  peak: {
    values: [
      [10800, 360.0, 12, 38, 71, 0.1, 0],
      [41, 1.4, 195, 352, 430, 0.0, 0],
      [251, 8.4, 97, 198, 268, 0.0, 0],
      [3, 0.1, 9850, 9850, 9850, 0.0, 0],
    ],
    interpretation:
      'În ziua de vârf de consum citirile de contor s-au dublat (de la 5.400 la 10.800 de cereri), iar latența lor p95 a ' +
      'crescut de la 23 ms la 38 ms, mult sub pragul de 2 s; nicio cerere respinsă și 0,1% erori reîncercate automat. ' +
      'Capacitatea actuală acoperă vârful; celelalte categorii au rămas sub 5% variație.',
  },
};

function peakVsNormal(normalScenario: string, peakScenario: string): PeakVsNormal {
  const day = PEAK_DAYS[peakScenario];
  const categories = NORMAL_DAY.map((c, i): CategoryComparison => {
    const v = day?.values[i] ?? [0, 0, 0, 0, 0, 0, 0];
    const peak = { requests: v[0], throughputPerMinute: v[1], latencyP50Ms: v[2], latencyP95Ms: v[3], latencyP99Ms: v[4], errorRatePercent: v[5], rejected: v[6] };
    const change = c.normal.latencyP95Ms ? ((peak.latencyP95Ms - c.normal.latencyP95Ms) / c.normal.latencyP95Ms) * 100 : 0;
    return { ...c, peak, p95ChangePercent: day ? Math.round(change * 10) / 10 : 0 };
  });
  return {
    generatedAt: nowIso(),
    normal: { scenario: normalScenario, from: '2026-11-14T10:00:00Z', to: '2026-11-14T10:30:00Z' },
    peak: { scenario: peakScenario, from: '2026-11-14T11:00:00Z', to: '2026-11-14T11:30:00Z' },
    categories,
    interpretation:
      day?.interpretation ??
      `Nu există rulări live etichetate „${peakScenario}”; rulează simulatorul cu X-Scenario: ${peakScenario} pentru comparație.`,
  };
}

const ANOMALIES: Anomalies = {
  generatedAt: '2026-11-14T13:05:00Z',
  from: '2026-11-13T13:05:00Z',
  to: '2026-11-14T13:05:00Z',
  rule: 'kwh > mediană + 3 × abatere standard, per contor',
  anomalies: {
    items: [
      { meterId: 'RO-BAN-0001377', zone: 'Banat', readingTs: '2026-11-14T03:15:00Z', kwh: 6.84, median: 0.62, stddev: 0.41, threshold: 1.85, deviations: 15.2 },
      { meterId: 'RO-MUN-0009920', zone: 'Muntenia', readingTs: '2026-11-14T08:45:00Z', kwh: 4.12, median: 0.55, stddev: 0.38, threshold: 1.69, deviations: 9.4 },
    ],
    page: 1,
    size: 20,
    totalItems: 2,
    totalPages: 1,
  },
  silentMeters: [{ meterId: 'RO-DOB-0000412', zone: 'Dobrogea', lastReadingTs: '2026-11-14T10:30:00Z', silentMinutes: 155 }],
  readingsByZoneHour: [
    { zone: 'Muntenia', hour: '2026-11-14T08:00:00Z', readings: 1240, totalKwh: 515.3 },
    { zone: 'Banat', hour: '2026-11-14T08:00:00Z', readings: 610, totalKwh: 244.9 },
    { zone: 'Dobrogea', hour: '2026-11-14T08:00:00Z', readings: 480, totalKwh: 198.2 },
  ],
  interpretation:
    'Au fost detectate 2 consumuri anormale, dintre care unul la contorul RO-BAN-0001377 (de 15 ori peste abaterea normală, ' +
    'la 03:15, posibilă defecțiune sau consum neautorizat). Contorul RO-DOB-0000412 din Dobrogea nu a mai trimis nimic de ' +
    '155 de minute: recomandăm verificarea comunicației.',
};

function anomalies(req: HttpRequest<unknown>): Observable<HttpEvent<unknown>> {
  const page = Number(req.params.get('page') ?? 1);
  const size = Number(req.params.get('size') ?? 20);
  const silent = Number(req.params.get('silentMinutes') ?? 60);
  if (![page, size, silent].every(Number.isInteger) || page < 1 || size < 1 || size > 100 || silent < 1) {
    return apiError(400, 'VALIDATION_ERROR', 'page ≥ 1, 1 ≤ size ≤ 100 și silentMinutes ≥ 1.');
  }
  const all = ANOMALIES.anomalies.items;
  const totalPages = Math.max(1, Math.ceil(all.length / size));
  return respond(200, {
    ...ANOMALIES,
    generatedAt: nowIso(),
    anomalies: { items: all.slice((page - 1) * size, page * size), page, size, totalItems: all.length, totalPages },
    silentMeters: ANOMALIES.silentMeters.filter((m) => m.silentMinutes >= silent),
  });
}

/** Avariile pe ore (UTC) per zonă; sumele dau clasamentul din contract (78 / 41 / 23). */
const HOURS: Record<'Muntenia' | 'Dobrogea' | 'Banat', Record<number, number>> = {
  Muntenia: { 6: 2, 7: 6, 8: 12, 9: 19, 10: 9, 11: 5, 12: 4, 13: 3, 14: 4, 15: 3, 16: 3, 17: 3, 18: 2, 19: 2, 20: 1 },
  Dobrogea: { 6: 1, 7: 4, 8: 9, 9: 7, 10: 5, 11: 3, 12: 3, 13: 2, 14: 2, 15: 2, 16: 1, 17: 1, 18: 1 },
  Banat: { 9: 1, 10: 2, 11: 2, 12: 3, 13: 4, 14: 6, 15: 3, 16: 1, 17: 1 },
};

const OUTAGE_ZONES: OutageZones = {
  generatedAt: '2026-11-14T13:05:00Z',
  from: '2026-10-15T13:05:00Z',
  to: '2026-11-14T13:05:00Z',
  totalOutages: 142,
  ranking: [
    { rank: 1, zone: 'Muntenia', outages: 78, sharePercent: 54.9, vsAverageRatio: 1.65, peakHour: 9 },
    { rank: 2, zone: 'Dobrogea', outages: 41, sharePercent: 28.9, vsAverageRatio: 0.87, peakHour: 8 },
    { rank: 3, zone: 'Banat', outages: 23, sharePercent: 16.2, vsAverageRatio: 0.49, peakHour: 14 },
  ],
  byHour: Object.entries(HOURS).flatMap(([zone, hours]) =>
    Object.entries(hours).map(([hour, outages]): ZoneHourOutages => ({ zone: zone as ZoneHourOutages['zone'], hour: Number(hour), outages })),
  ),
  interpretation:
    'Muntenia a avut de 3,4 ori mai multe avarii decât Banat (78 față de 23), cu un vârf la ora 09:00; recomandăm ' +
    'capacitate suplimentară de intervenție în zonă în intervalul 08:00–10:00.',
};

/** Interval fără avarii: O7 păstrează NULL-urile din SQL (procente, rapoarte și ore de vârf nedefinite). */
const NO_OUTAGES: OutageZones = {
  ...OUTAGE_ZONES,
  totalOutages: 0,
  ranking: OUTAGE_ZONES.ranking.map((z) => ({ ...z, rank: 1, outages: 0, sharePercent: null, vsAverageRatio: null, peakHour: null })),
  byHour: [],
  interpretation: 'Nu a fost înregistrată nicio avarie în intervalul ales; nu există zone de prioritizat.',
};

/** Banat fără avarii, celelalte zone cu avarii: 0 real (0% și ×0,00), doar ora de vârf e null. */
const ZONE_WITHOUT_OUTAGES: OutageZones = {
  ...OUTAGE_ZONES,
  totalOutages: 119,
  ranking: [
    { rank: 1, zone: 'Muntenia', outages: 78, sharePercent: 65.5, vsAverageRatio: 1.97, peakHour: 9 },
    { rank: 2, zone: 'Dobrogea', outages: 41, sharePercent: 34.5, vsAverageRatio: 1.03, peakHour: 8 },
    { rank: 3, zone: 'Banat', outages: 0, sharePercent: 0, vsAverageRatio: 0, peakHour: null },
  ],
  byHour: OUTAGE_ZONES.byHour.filter((h) => h.zone !== 'Banat'),
  interpretation:
    'Muntenia a avut 78 de avarii, cu vârf la ora 09:00; în Banat nu a fost nicio avarie în intervalul ales. ' +
    'Recomandăm capacitate suplimentară în Muntenia în intervalul 08:00–10:00.',
};

function outageZones(mode: MockStatsMode): OutageZones {
  return mode === 'no-outages' ? NO_OUTAGES : mode === 'zone-without-outages' ? ZONE_WITHOUT_OUTAGES : OUTAGE_ZONES;
}

/** details din 503-urile O7 (docs/api/o7-analytics.md, „C12 and shared contract gaps”). */
const MISSING_METRICS: Record<string, Record<string, unknown>> = {
  '/stats/peak-vs-normal': {
    reason: 'REQUEST_METRICS_UNAVAILABLE',
    unavailableFields: ['requests', 'throughputPerMinute', 'rejected'],
  },
  '/stats/storage-advisor': { reason: 'STORAGE_MEASUREMENTS_INCOMPLETE' },
};

// ---------- situația simulată ----------

/**
 * normal = exemplele din contract; zone-without-outages = o zonă cu 0 avarii; no-outages = interval fără
 * avarii (null-uri); metrics-missing = 503 pe cele două statistici de performanță (lipsesc măsurătorile C12).
 */
export type MockStatsMode = 'normal' | 'zone-without-outages' | 'no-outages' | 'metrics-missing';

export const MOCK_STATS_MODES: readonly { value: MockStatsMode; label: string }[] = [
  { value: 'normal', label: 'Date normale' },
  { value: 'zone-without-outages', label: 'O zonă fără avarii' },
  { value: 'no-outages', label: 'Interval fără avarii' },
  { value: 'metrics-missing', label: 'Lipsesc măsurătorile (503)' },
];

const MODE_KEY = 'filegrid.mock.stats';

export function getMockStatsMode(): MockStatsMode {
  try {
    const raw = localStorage.getItem(MODE_KEY);
    return MOCK_STATS_MODES.some((m) => m.value === raw) ? (raw as MockStatsMode) : 'normal';
  } catch {
    return 'normal';
  }
}

export function setMockStatsMode(mode: MockStatsMode): void {
  try {
    localStorage.setItem(MODE_KEY, mode);
  } catch {
    /* ignorat */
  }
}

const REQUIREMENTS: Requirements = {
  generatedAt: '2026-11-14T13:05:00Z',
  requirements: [
    { id: 'R1', category: 'METER_READING', title: 'Nicio citire nu se pierde și nu se dublează', status: 'OK', message: '0 citiri neconfirmate, 0 citiri abandonate.', thresholds: { unconfirmedMax: 0, abandonedMax: 0 }, current: { unconfirmed: 0, abandoned: 0 } },
    { id: 'R2', category: 'OUTAGE_RECORD', title: 'Avariile sunt disponibile imediat pentru dispecerat', status: 'AT_RISK', message: 'Latența p95 pentru avarii este 2,4 s în ultimele 5 minute (prag 2 s).', thresholds: { p95MaxSeconds: 2.0, windowMinutes: 5 }, current: { p95Seconds: 2.4, samples: 37, sufficientData: true } },
    { id: 'R3', category: 'CONNECTION_DOC', title: 'Datele personale rămân protejate și verificabile la audit', status: 'OK', message: 'Criptare activă, 0 accesuri refuzate în ultimele 5 minute.', thresholds: { encryptionRequired: true, deniedMax: 0, windowMinutes: 5 }, current: { encryptionActive: true, denied: 0 } },
    { id: 'R4', category: 'INSPECTION_VIDEO', title: 'Transferul rezistă pe conexiuni lente sau instabile', status: 'OK', message: 'Date insuficiente: 3 din minim 5 transferuri video în ultimele 5 minute.', thresholds: { failedMaxPercent: 5.0 }, current: { failedPercent: 0.0, samples: 3, sufficientData: false } },
  ],
};
