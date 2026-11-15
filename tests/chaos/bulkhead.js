// F9 f) Bulkhead (C9): meter reading spike above the METER_READING limit (50 concurrent requests per instance) while
// the field user uploads outages and the dispatcher searches. Expected: 429 CATEGORY_SATURATED only on METER_READING;
// outages get 201 and searches 200. Reuses the F8 library (tests/load/lib/common.js). Run by f-bulkhead.ps1.
//   STORM_VUS (default 150), STORM_SEC (default 30)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { auth, meterBatch, csv, ZONES, METER_URL, BASE_URL, rnd } from '../load/lib/common.js';

const RUN = __ENV.RUN_ID || `chaos-${Date.now().toString(36)}`;
const VUS = Number(__ENV.STORM_VUS || 150);
const SEC = Number(__ENV.STORM_SEC || 30);
const CODES = { Muntenia: 'MUN', Banat: 'BAN', Dobrogea: 'DOB' };

const meter201 = new Counter('chaos_meter_201');
const meter429 = new Counter('chaos_meter_429');
const meter429WrongCode = new Counter('chaos_meter_429_wrong_code');
const meterOther = new Counter('chaos_meter_other');
const outageOk = new Rate('chaos_outage_201');
const outage429 = new Counter('chaos_outage_429');
const searchOk = new Rate('chaos_search_200');
const search429 = new Counter('chaos_search_429');

export const options = {
  insecureSkipTLSVerify: true,
  scenarios: {
    meter_storm: {
      executor: 'ramping-vus', exec: 'meterStorm', startVUs: 0,
      stages: [{ duration: '5s', target: VUS }, { duration: `${SEC - 5}s`, target: VUS }], gracefulRampDown: '3s',
    },
    outage_upload: {
      executor: 'constant-arrival-rate', exec: 'outage', rate: 2, timeUnit: '1s', duration: `${SEC - 5}s`,
      startTime: '5s', preAllocatedVUs: 4, maxVUs: 20,
    },
    dispatcher_search: {
      executor: 'constant-arrival-rate', exec: 'searchProbe', rate: 2, timeUnit: '1s', duration: `${SEC - 5}s`,
      startTime: '5s', preAllocatedVUs: 4, maxVUs: 20,
    },
  },
  thresholds: {
    chaos_meter_429: ['count>0'], // METER_READING really is saturated
    chaos_meter_429_wrong_code: ['count==0'], // every 429 carries code CATEGORY_SATURATED
    chaos_outage_201: ['rate==1'], // every outage goes through
    chaos_outage_429: ['count==0'], // no rejections on OUTAGE_RECORD
    chaos_search_200: ['rate==1'],
    chaos_search_429: ['count==0'],
    // only so k6 computes per-operation p95 for the summary (not a real threshold)
    'http_req_duration{op:meter_ingest}': ['p(95)>=0'],
    'http_req_duration{op:outage_upload}': ['p(95)>=0'],
    'http_req_duration{op:search}': ['p(95)>=0'],
  },
};

let meter = null;
let day = 0;
export function meterStorm() {
  if (!meter) {
    const zone = ZONES[__VU % 3];
    meter = { zone, id: `${RUN}-${CODES[zone]}-${String(__VU).padStart(7, '0')}` };
  }
  const b = meterBatch(meter.id, meter.zone, Date.UTC(2026, 8, 1) + day * 86400000, 96);
  const res = http.post(`${METER_URL}/meter-readings`, b.body, {
    headers: auth('contor', { 'Idempotency-Key': b.key, 'X-Scenario': 'chaos', 'Content-Type': 'application/json' }),
    tags: { op: 'meter_ingest' }, responseCallback: http.expectedStatuses(200, 201, 429),
  });
  if (res.status === 201) { meter201.add(1); day++; }
  else if (res.status === 429) {
    meter429.add(1);
    let code = ''; try { code = res.json('code'); } catch (_) { /* non-JSON body */ }
    if (code !== 'CATEGORY_SATURATED' || !res.headers['Retry-After']) meter429WrongCode.add(1);
  } else if (res.status !== 200) meterOther.add(1);
  // short pause on 429 (not the full Retry-After): keeps the bulkhead full without choking the test machine
  if (res.status === 429) sleep(0.05);
}

export function outage() {
  const name = `${RUN}-avarie-bulkhead-${__VU}-${__ITER}-${rnd(1e6)}.csv`;
  const res = http.post(`${BASE_URL}/files`, {
    category: 'OUTAGE_RECORD', zone: ZONES[rnd(3)], occurredAt: new Date().toISOString(), stationId: 'ST-CHAOS-02',
    file: http.file(csv(2048), name, 'text/csv'),
  }, { headers: auth('teren', { 'X-Scenario': 'chaos' }), tags: { op: 'outage_upload' }, responseCallback: http.expectedStatuses(201) });
  outageOk.add(res.status === 201);
  if (res.status === 429) outage429.add(1);
  if (res.status !== 201) console.warn(`outage -> ${res.status} ${String(res.body).slice(0, 200)}`);
  check(res, { 'outage during the spike -> 201': (r) => r.status === 201 });
}

export function searchProbe() {
  const res = http.get(`${BASE_URL}/files?category=OUTAGE_RECORD&size=10`, {
    headers: auth('dispecer'), tags: { op: 'search' }, responseCallback: http.expectedStatuses(200),
  });
  searchOk.add(res.status === 200);
  if (res.status === 429) search429.add(1);
  check(res, { 'search during the spike -> 200': (r) => r.status === 200 });
}

function val(data, name, field) {
  const m = data.metrics[name];
  return m ? m.values[field] : 0;
}

function p95(data, op) {
  const m = data.metrics[`http_req_duration{op:${op}}`];
  return m ? Math.round(m.values['p(95)']) : null;
}

export function handleSummary(data) {
  const r = {
    vus: VUS, seconds: SEC,
    meter201: val(data, 'chaos_meter_201', 'count'),
    meter429: val(data, 'chaos_meter_429', 'count'),
    meter429WrongCode: val(data, 'chaos_meter_429_wrong_code', 'count'),
    meterOther: val(data, 'chaos_meter_other', 'count'),
    outageTotal: data.metrics.chaos_outage_201 ? data.metrics.chaos_outage_201.values.passes + data.metrics.chaos_outage_201.values.fails : 0,
    outageFail: data.metrics.chaos_outage_201 ? data.metrics.chaos_outage_201.values.fails : 0,
    outage429: val(data, 'chaos_outage_429', 'count'),
    searchTotal: data.metrics.chaos_search_200 ? data.metrics.chaos_search_200.values.passes + data.metrics.chaos_search_200.values.fails : 0,
    searchFail: data.metrics.chaos_search_200 ? data.metrics.chaos_search_200.values.fails : 0,
    search429: val(data, 'chaos_search_429', 'count'),
    p95: { meter: p95(data, 'meter_ingest'), outage: p95(data, 'outage_upload'), search: p95(data, 'search') },
  };
  return { stdout: `CHAOS_BULKHEAD ${JSON.stringify(r)}\n` };
}
