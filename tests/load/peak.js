// Short spike (F8): shows per-category isolation (bulkhead C9). A meter reading storm with many concurrent
// requests (above METER_READING's limit of 50) must not hurt search and outage latency.
// The probes (dispatcher searching, field user uploading outages) run at a constant rate: first alone (baseline),
// then during the storm. p95 is compared between the two phases.
//   SHORT=1     -> 15 s baseline + 30 s storm
//   PEAK_RATE   -> storm VUs (default 150; each sends batches in a tight loop; on 429/503 it waits for
//                  Retry-After, like meter-simulator)
// The storm goes straight to api (METER_BASE_URL=http://api:8080/api/v1 on storage-net, like the simulator); probes
// go through nginx, like users. Through nginx, above ~500 new connections/s the nginx -> api ephemeral ports run out
// (proxy_pass with a variable, no upstream keepalive) and 502s unrelated to the bulkhead appear.
import { check } from 'k6';
import {
  RUN_ID, ZONES, METER_URL, BASE_URL, ingest, honorRetryAfter, meterBatch, meterId, uploadOutage, search, download, pick, rnd,
} from './lib/common.js';
import { makeSummary } from './lib/summary.js';

const SHORT = !!__ENV.SHORT;
const BASE = SHORT ? 15 : 30;   // seconds
const STORM = SHORT ? 30 : 60;  // seconds
const VUS = Number(__ENV.PEAK_RATE || 150);
const SCEN = 'k6-peak';

function probe(exec, perSec, start, dur, phase) {
  return {
    executor: 'constant-arrival-rate', exec, rate: perSec, timeUnit: '1s', duration: `${dur}s`, startTime: `${start}s`,
    preAllocatedVUs: perSec * 2, maxVUs: perSec * 8, tags: { phase },
  };
}

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    search_baseline: probe('probeSearch', 5, 0, BASE, 'baseline'),
    outage_baseline: probe('probeOutage', 2, 0, BASE, 'baseline'),
    search_storm: probe('probeSearch', 5, BASE, STORM, 'storm'),
    outage_storm: probe('probeOutage', 2, BASE, STORM, 'storm'),
    meter_storm: {
      executor: 'ramping-vus', exec: 'meterStorm', startTime: `${BASE}s`, startVUs: 0, tags: { phase: 'storm' },
      stages: [
        { duration: '5s', target: VUS },
        { duration: `${STORM - 10}s`, target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // probe latency during the storm stays under the threshold (baseline values show up in the summary too)
    'http_req_duration{op:search,phase:baseline}': ['p(95)<800'],
    'http_req_duration{op:search,phase:storm}': ['p(95)<800'],
    'http_req_duration{op:outage_upload,phase:baseline}': ['p(95)<1500'],
    'http_req_duration{op:outage_upload,phase:storm}': ['p(95)<1500'],
    'http_req_duration{op:meter_ingest,phase:storm}': ['p(95)<3000'],
    // probes get no errors and no 429 (another category's bulkhead does not touch them)
    'filegrid_unexpected{op:search}': ['rate<0.01'],
    'filegrid_unexpected{op:outage_upload}': ['rate<0.01'],
    'filegrid_bulkhead_429{op:search}': ['count==0'],
    'filegrid_bulkhead_429{op:outage_upload}': ['count==0'],
    // storm: 429 is expected and counted separately; real errors (5xx, unexpected 4xx) stay rare
    'filegrid_unexpected{op:meter_ingest}': ['rate<0.05'],
    'filegrid_bulkhead_429{op:meter_ingest}': ['count>=0'],
  },
};

export function probeSearch() {
  const s = search('dispecer', pick([{ category: 'OUTAGE_RECORD' }, { category: 'OUTAGE_RECORD', q: 'k6-' }, { q: 'avarie' }]));
  if (s.status === 200 && rnd(4) === 0) {
    const items = s.json('items').filter((f) => f.category === 'OUTAGE_RECORD');
    if (items.length) download('dispecer', pick(items).id);
  }
}

export function probeOutage() {
  const up = uploadOutage('teren', SCEN, 2048 + rnd(8192));
  check(up.res, { 'outage during the spike -> 201': (r) => r.status === 201 });
}

let meter = null;
let day = 0;
export function meterStorm() {
  if (!meter) { const zone = ZONES[__VU % 3]; meter = { zone, id: meterId(`${RUN_ID}p`, zone, __VU) }; }
  const b = meterBatch(meter.id, meter.zone, Date.UTC(2026, 2, 1) + day * 86400000, 96);
  const res = ingest('contor', b, SCEN);
  if (res.status === 201) day++;
  honorRetryAfter(res);
}

function p95(data, name) {
  const m = data.metrics[name];
  return m ? m.values['p(95)'] : undefined;
}

export const handleSummary = makeSummary('peak', (data) => {
  const out = ['Per-category isolation (p95, baseline -> storm):'];
  for (const op of ['search', 'outage_upload']) {
    const b = p95(data, `http_req_duration{op:${op},phase:baseline}`);
    const s = p95(data, `http_req_duration{op:${op},phase:storm}`);
    if (b === undefined || s === undefined) continue;
    const ok = s <= Math.max(3 * b, b + 200);
    out.push(`  ${op.padEnd(14)} ${b.toFixed(1)} ms -> ${s.toFixed(1)} ms (x${(s / b).toFixed(2)}) ${ok ? 'ISOLATED' : 'DEGRADED'}`);
  }
  const m = data.metrics['http_req_duration{op:meter_ingest,phase:storm}'];
  const rej = data.metrics['filegrid_bulkhead_429{op:meter_ingest}'];
  out.push(`  probes via ${BASE_URL}; readings via ${METER_URL}`);
  out.push(`  storm readings: ${VUS} VU, p95=${m ? m.values['p(95)'].toFixed(1) : '-'} ms, 429=${rej ? rej.values.count : 0}`);
  return out;
});
