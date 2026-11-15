// Reading ingestion (F8): simulator-like batches (role contor / METER_SERVICE), with deliberate duplicates.
// 429 CATEGORY_SATURATED (bulkhead C9) is counted separately in filegrid_bulkhead_429, not as an error.
//   SHORT=1      -> 20 s, 20 batches/s
//   METER_RATE   -> batches per second at the plateau (default 40)
//   DURATION     -> plateau duration (default 1m)
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

// Per-VU state: its own meter, advancing in synthetic time (one day per batch), like the simulator.
let mine = null;
let day = 0;
let last = null;

export default function () {
  if (!mine) {
    const zone = ZONES[__VU % ZONES.length];
    mine = { zone, id: meterId(RUN_ID, zone, __VU) };
  }
  // deliberate duplicate: resend the last confirmed batch with the same key -> 200 duplicate:true
  if (last && rnd(100) < DUP_PCT) {
    const res = ingest('contor', last, 'k6-normal');
    check(res, { 'duplicate -> 200 duplicate:true (or 429)': (r) => r.status === 429 || (r.status === 200 && r.json('duplicate') === true) });
    return;
  }
  const n = 48 + rnd(240); // 48..287 readings, ~3-20 KB
  const batch = meterBatch(mine.id, mine.zone, Date.UTC(2026, 0, 1) + day * 86400000 * 3, n);
  const res = ingest('contor', batch, 'k6-normal');
  check(res, { 'new batch -> 201 (or 429)': (r) => r.status === 201 || r.status === 429 });
  if (res.status === 201) { last = batch; day++; }
  // on 429 the batch does not advance: the VU's next iteration sends another batch for the same day (new key, no overlap)
}

export const handleSummary = makeSummary('meter-readings', (data) => {
  const m = data.metrics['http_req_duration{op:meter_ingest}'];
  return m ? [`Ingestie: p95=${Math.round(m.values['p(95)'])} ms, p99=${Math.round(m.values['p(99)'])} ms`] : [];
});
