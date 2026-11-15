// Furtuna de avarii (F8, planul echipei): faza `normal` pe toate categoriile, apoi faza `storm`, in care avariile
// (OUTAGE_RECORD, POST /files) cresc de 10 ori, iar celelalte categorii continua la rata normala.
// Header-ul X-Scenario = `normal`, apoi `storm` (valorile din contract), deci /stats/peak-vs-normal si Grafana
// (filegrid_operation_seconds pe categorie) arata ca doar avariile cresc.
//   SHORT=1      -> 45 s normal + 45 s furtuna (altfel 2 min + 2 min)
//   STORM_FACTOR -> multiplicatorul avariilor in furtuna (implicit 10)
import { check, sleep } from 'k6';
import {
  RUN_ID, ZONES, ingest, meterBatch, meterId, uploadOutage, uploadDoc, uploadVideo, search, download, verify, pick, rnd,
} from './lib/common.js';
import { makeSummary } from './lib/summary.js';

const SHORT = !!__ENV.SHORT;
const PHASE = SHORT ? 45 : 120; // secunde per faza
const FACTOR = Number(__ENV.STORM_FACTOR || 10);
const OUTAGE_RATE = 2;           // avarii/s in faza normala

function arrival(exec, perSec, phase, vus) {
  return {
    executor: 'constant-arrival-rate', exec, rate: Math.round(perSec * 10), timeUnit: '10s', duration: `${PHASE}s`,
    startTime: phase === 'storm' ? `${PHASE}s` : '0s', preAllocatedVUs: vus, maxVUs: vus * 4,
    tags: { phase }, env: { XSCEN: phase },
  };
}

function both(name, exec, perSec, vus) {
  return { [`${name}_normal`]: arrival(exec, perSec, 'normal', vus), [`${name}_storm`]: arrival(exec, perSec, 'storm', vus) };
}

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: Object.assign(
    {
      outage_normal: arrival('outage', OUTAGE_RATE, 'normal', 5),
      outage_storm: arrival('outage', OUTAGE_RATE * FACTOR, 'storm', 20),
    },
    both('search', 'dispatchSearch', 3, 6),
    both('docs', 'docs', 1, 4),
    both('meters', 'meters', 5, 6),
    both('video', 'video', 0.2, 2),
  ),
  thresholds: {
    // celelalte categorii isi pastreaza latenta si in furtuna
    'http_req_duration{op:search,phase:normal}': ['p(95)<800'],
    'http_req_duration{op:search,phase:storm}': ['p(95)<800'],
    'http_req_duration{op:doc_upload,phase:normal}': ['p(95)<1500'],
    'http_req_duration{op:doc_upload,phase:storm}': ['p(95)<1500'],
    'http_req_duration{op:meter_ingest,phase:normal}': ['p(95)<500'],
    'http_req_duration{op:meter_ingest,phase:storm}': ['p(95)<500'],
    'http_req_duration{op:video_chunk,phase:normal}': ['p(95)<3000'],
    'http_req_duration{op:video_chunk,phase:storm}': ['p(95)<3000'],
    // ...si nu primesc erori
    'filegrid_unexpected{op:search}': ['rate<0.01'],
    'filegrid_unexpected{op:doc_upload}': ['rate<0.01'],
    'filegrid_unexpected{op:meter_ingest}': ['rate<0.01'],
    'filegrid_unexpected{op:video_chunk}': ['rate<0.01'],
    // avariile: 429 (bulkhead OUTAGE_RECORD) numarate separat; erorile reale raman rare
    'http_req_duration{op:outage_upload,phase:normal}': ['p(95)<1500'],
    'http_req_duration{op:outage_upload,phase:storm}': ['p(95)<3000'],
    'filegrid_unexpected{op:outage_upload}': ['rate<0.05'],
    'filegrid_bulkhead_429{op:outage_upload}': ['count>=0'],
    filegrid_integrity_mismatch: ['count==0'],
  },
};

export function outage() {
  // fisiere mici (2-32 KB): furtuna inseamna numar de cereri, nu volum pe disc
  const up = uploadOutage('teren', __ENV.XSCEN, 2048 + rnd(30 * 1024));
  check(up.res, { 'avarie -> 201 (sau 429)': (r) => r.status === 201 || r.status === 429 });
}

export function dispatchSearch() {
  const s = search('dispecer', pick([{ category: 'OUTAGE_RECORD' }, { category: 'METER_READING', size: 20 }, { q: 'avarie' }]));
  if (s.status !== 200 || rnd(3) !== 0) return;
  const items = s.json('items').filter((f) => f.category === 'OUTAGE_RECORD');
  if (items.length) download('dispecer', pick(items).id);
}

export function docs() {
  const up = uploadDoc('racordari', __ENV.XSCEN, 2048 + rnd(32 * 1024));
  if (up.res.status === 201 && rnd(2) === 0) verify('racordari', up.res.json('id'), true);
}

let meter = null;
let day = 0;
export function meters() {
  if (!meter) { const zone = ZONES[__VU % 3]; meter = { zone, id: meterId(`${RUN_ID}w`, zone, __VU) }; }
  const b = meterBatch(meter.id, meter.zone, Date.UTC(2026, 3, 1) + day * 86400000, 96);
  if (ingest('contor', b, __ENV.XSCEN).status === 201) day++;
}

export function video() {
  const r = uploadVideo('teren', __ENV.XSCEN, 128 * 1024 + rnd(256 * 1024));
  check(r.res, { 'video confirmat (sau 429)': () => r.ok });
  sleep(0.5);
}

export const handleSummary = makeSummary('storm', (data) => {
  const out = ['Furtuna de avarii (p95 normal -> storm; cereri):'];
  for (const op of ['outage_upload', 'search', 'doc_upload', 'meter_ingest', 'video_chunk']) {
    const n = data.metrics[`http_req_duration{op:${op},phase:normal}`];
    const s = data.metrics[`http_req_duration{op:${op},phase:storm}`];
    if (!n || !s) continue;
    out.push(`  ${op.padEnd(14)} ${n.values['p(95)'].toFixed(1)} ms -> ${s.values['p(95)'].toFixed(1)} ms`);
  }
  const rej = data.metrics['filegrid_bulkhead_429{op:outage_upload}'];
  out.push(`  avarii respinse de bulkhead (429): ${rej ? rej.values.count : 0}; factor furtuna: x${FACTOR}`);
  return out;
});
