// Ingestie de citiri (F8): loturi ca simulatorul (rolul contor / METER_SERVICE), cu duplicate intentionate.
// 429 CATEGORY_SATURATED (bulkhead C9) se numara separat in filegrid_bulkhead_429, nu ca eroare.
//   SHORT=1      -> 20 s, 20 loturi/s
//   METER_RATE   -> loturi pe secunda in palier (implicit 40)
//   DURATION     -> durata palierului (implicit 1m)
import { check } from 'k6';
import { RUN_ID, ZONES, ingest, meterBatch, meterId, rnd } from './lib/common.js';
import { makeSummary } from './lib/summary.js';

const SHORT = !!__ENV.SHORT;
const RATE = Number(__ENV.METER_RATE || (SHORT ? 20 : 40));
const HOLD = __ENV.DURATION || (SHORT ? '20s' : '1m');
const DUP_PCT = Number(__ENV.DUP_PCT || 10);

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    meters: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.floor(RATE / 4)),
      timeUnit: '1s',
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: RATE * 4,
      stages: [
        { duration: SHORT ? '5s' : '15s', target: RATE },
        { duration: HOLD, target: RATE },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{op:meter_ingest}': ['p(95)<500', 'p(99)<1500'],
    'filegrid_unexpected{op:meter_ingest}': ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    filegrid_meter_duplicates: ['count>0'],
  },
};

// Stare per VU: un contor propriu, care avanseaza in timp sintetic (o zi per lot), ca la simulator.
let mine = null;
let day = 0;
let last = null;

export default function () {
  if (!mine) {
    const zone = ZONES[__VU % ZONES.length];
    mine = { zone, id: meterId(RUN_ID, zone, __VU) };
  }
  // duplicat intentionat: retrimite ultimul lot confirmat, cu aceeasi cheie -> 200 duplicate:true
  if (last && rnd(100) < DUP_PCT) {
    const res = ingest('contor', last, 'k6-normal');
    check(res, { 'duplicat -> 200 duplicate:true (sau 429)': (r) => r.status === 429 || (r.status === 200 && r.json('duplicate') === true) });
    return;
  }
  const n = 48 + rnd(240); // 48..287 citiri, ~3–20 KB
  const batch = meterBatch(mine.id, mine.zone, Date.UTC(2026, 0, 1) + day * 86400000 * 3, n);
  const res = ingest('contor', batch, 'k6-normal');
  check(res, { 'lot nou -> 201 (sau 429)': (r) => r.status === 201 || r.status === 429 });
  if (res.status === 201) { last = batch; day++; }
  // la 429 lotul nu avanseaza: urmatoarea iteratie a VU-ului retrimite alt lot in aceeasi zi (cheie noua, fara suprapunere)
}

export const handleSummary = makeSummary('meter-readings', (data) => {
  const m = data.metrics['http_req_duration{op:meter_ingest}'];
  return m ? [`Ingestie: p95=${Math.round(m.values['p(95)'])} ms, p99=${Math.round(m.values['p(99)'])} ms`] : [];
});
