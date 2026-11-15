// Incarcare mixta pe roluri (F8): avarii (POST /files), video chunked (/uploads/*), documente CONNECTION_DOC
// criptate, cautare/descarcare/verify si /stats/* pentru dispecer, cu ingestie de citiri in fundal.
//   SHORT=1     -> 30 s
//   MIXED_RATE  -> multiplicator al ratelor (implicit 1)
//   DURATION    -> durata (implicit 2m)
import { check, sleep } from 'k6';
import {
  RUN_ID, ZONES, ingest, meterBatch, meterId, uploadOutage, uploadDoc, uploadVideo, search, download, verify,
  downloadLink, stats, STATS, pick, rnd,
} from './lib/common.js';
import { makeSummary } from './lib/summary.js';

const SHORT = !!__ENV.SHORT;
const DUR = __ENV.DURATION || (SHORT ? '30s' : '2m');
const K = Number(__ENV.MIXED_RATE || 1);
const SCEN = 'k6-mixed';

function rate(name, perSec, vus) {
  return {
    executor: 'constant-arrival-rate', exec: name, rate: Math.max(1, Math.round(perSec * K * 10)), timeUnit: '10s',
    duration: DUR, preAllocatedVUs: vus, maxVUs: vus * 4,
  };
}

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    field_outage: rate('fieldOutage', 2, 5),         // teren: avarii mici + verificare
    field_video: { executor: 'constant-vus', exec: 'fieldVideo', vus: 2, duration: DUR },
    connections_docs: rate('connectionsDocs', 1, 4), // racordari: documente criptate
    dispatch_search: rate('dispatchSearch', 4, 8),   // dispecer: cautare + descarcare + verify
    dispatch_stats: rate('dispatchStats', 0.5, 2),   // dispecer: /stats/*
    meters_bg: rate('meters', 10, 10),               // contor: citiri in fundal
  },
  thresholds: {
    'http_req_duration{op:search}': ['p(95)<800'],
    'http_req_duration{op:outage_upload}': ['p(95)<1500'],
    'http_req_duration{op:doc_upload}': ['p(95)<1500'],
    'http_req_duration{op:download}': ['p(95)<1000'],
    'http_req_duration{op:verify}': ['p(95)<1000'],
    'http_req_duration{op:video_chunk}': ['p(95)<3000'],
    'http_req_duration{op:video_complete}': ['p(95)<3000'],
    'http_req_duration{op:meter_ingest}': ['p(95)<500'],
    'http_req_duration{op:stats}': ['p(95)<2000'],
    filegrid_unexpected: ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    filegrid_integrity_mismatch: ['count==0'],
  },
};

let outageIds = [];

export function fieldOutage() {
  const up = uploadOutage('teren', SCEN, 1024 + rnd(32 * 1024));
  if (up.res.status !== 201) return;
  const id = up.res.json('id');
  outageIds.push(id);
  if (outageIds.length > 50) outageIds = outageIds.slice(-50);
  if (rnd(3) === 0) verify('teren', id);
}

export function fieldVideo() {
  const r = uploadVideo('teren', SCEN, 128 * 1024 + rnd(512 * 1024));
  check(r.res, { 'video confirmat (sau 429)': () => r.ok });
  if (r.ok && r.res.json('id')) verify('teren', r.res.json('id'));
  sleep(1);
}

export function connectionsDocs() {
  const up = uploadDoc('racordari', SCEN, 2048 + rnd(64 * 1024));
  if (up.res.status !== 201) return;
  const id = up.res.json('id');
  const d = download('racordari', id);
  check(d.res, { 'document descarcat = original': (r) => r.status === 429 || r.headers['X-Content-Sha256'] === up.res.json('sha256Plain') });
  verify('racordari', id, true);
}

export function dispatchSearch() {
  const s = search('dispecer', pick([
    { category: 'OUTAGE_RECORD', q: 'k6-' },
    { category: 'OUTAGE_RECORD', page: 1 + rnd(3) },
    { category: 'METER_READING', size: 20 },
    { q: 'avarie' },
  ]));
  if (s.status !== 200) return;
  const items = s.json('items').filter((f) => f.category === 'OUTAGE_RECORD');
  if (!items.length) return;
  const f = pick(items);
  const n = rnd(10);
  if (n < 4) download('dispecer', f.id);
  else if (n < 6) download('dispecer', f.id, 'bytes=0-511');
  else if (n < 8) verify('dispecer', f.id);
  else downloadLink('dispecer', f.id);
}

export function dispatchStats() {
  stats('dispecer', pick(STATS));
}

let meter = null;
let day = 0;
export function meters() {
  if (!meter) { const zone = ZONES[__VU % 3]; meter = { zone, id: meterId(`${RUN_ID}m`, zone, __VU) }; }
  const b = meterBatch(meter.id, meter.zone, Date.UTC(2026, 1, 1) + day * 86400000, 96);
  const res = ingest('contor', b, SCEN);
  if (res.status === 201) day++;
}

export const handleSummary = makeSummary('mixed');
