// Shared library for the FileGrid k6 tests (F8). Contract: docs/api/openapi.yaml.
// Passwords come only from environment variables (run.ps1 / run.sh read them from .env); they are never written anywhere.
import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Counter, Rate, Trend } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || 'https://nginx/api/v1').replace(/\/$/, '');
// Meter readings may go straight to api on storage-net (like meter-simulator), bypassing nginx: METER_BASE_URL.
export const METER_URL = (__ENV.METER_BASE_URL || BASE_URL).replace(/\/$/, '');
// Run id: part of meterId and file names, so k6 data never mixes with real data.
export const RUN_ID = __ENV.RUN_ID || Date.now().toString(36);

export const USERS = {
  dispecer: { role: 'DISPATCH', env: 'FILEGRID_USER_DISPECER_PASSWORD' },
  teren: { role: 'FIELD', env: 'FILEGRID_USER_TEREN_PASSWORD' },
  racordari: { role: 'CONNECTIONS', env: 'FILEGRID_USER_RACORDARI_PASSWORD' },
  contor: { role: 'METER_SERVICE', env: 'FILEGRID_USER_CONTOR_PASSWORD' },
  admin: { role: 'ADMIN', env: 'FILEGRID_USER_ADMIN_PASSWORD' },
};

export const ZONES = ['Muntenia', 'Banat', 'Dobrogea'];
const ZONE_CODES = { Muntenia: 'MUN', Banat: 'BAN', Dobrogea: 'DOB' };

// --- Custom metrics -----------------------------------------------------------------------------------------------
// unexpected: 1 when the HTTP status is not in the operation's expected list (a real error), 0 otherwise.
export const unexpected = new Rate('filegrid_unexpected');
// 429 CATEGORY_SATURATED from the bulkhead (C9): counted separately, not errors.
export const saturated = new Counter('filegrid_bulkhead_429');
// 403s received on purpose by the security checks (C10).
export const forbiddenOk = new Counter('filegrid_forbidden_403');
export const meterDuplicates = new Counter('filegrid_meter_duplicates');
export const integrityFail = new Counter('filegrid_integrity_mismatch');
export const opDuration = new Trend('filegrid_op_ms', true);

// --- Tokens ----------------------------------------------------------------------------------------------------------
const tokens = {}; // per-VU cache: { user: { token, at } }

export function password(user) {
  const v = __ENV[USERS[user].env];
  if (!v) throw new Error(`Missing variable ${USERS[user].env} (run through run.ps1 / run.sh).`);
  return v;
}

export function login(user) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ username: user, password: password(user) }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { op: 'login', name: 'POST /auth/login' },
  });
  track(res, [200], 'login');
  check(res, { [`login ${user} 200`]: (r) => r.status === 200 });
  if (res.status !== 200) return null;
  return res.json('accessToken');
}

// Tokens are valid for 15 min; we re-login after 10 min.
export function token(user) {
  const t = tokens[user];
  if (t && Date.now() - t.at < 10 * 60 * 1000) return t.token;
  const fresh = login(user);
  if (fresh) tokens[user] = { token: fresh, at: Date.now() };
  else sleep(1); // login failed (e.g. 502/503): do not hammer /auth/login in a loop
  return fresh;
}

// Default X-Scenario for every authenticated request (storm.js sets it per phase through the scenario `env`).
export function auth(user, extra) {
  if (__ENV.XSCEN) extra = Object.assign({ 'X-Scenario': __ENV.XSCEN }, extra || {});
  return Object.assign({ Authorization: `Bearer ${token(user)}` }, extra || {});
}

// --- Result tracking ------------------------------------------------------------------------------------------------
// expected: status codes that count as success. A 429 is counted separately whenever it shows up (if listed).
export function track(res, expected, op, category) {
  const ok = expected.indexOf(res.status) >= 0;
  unexpected.add(!ok, { op });
  if (res.status === 429) {
    let code = '';
    try { code = res.json('code'); } catch (_) { /* non-JSON body */ }
    saturated.add(1, { op, category: category || '', code: code || '' });
  }
  opDuration.add(res.timings.duration, { op });
  if (!ok && __ENV.K6_DEBUG_ERRORS) {
    console.warn(`[${op}] ${res.request.method} ${res.url} -> ${res.status} ${String(res.body).slice(0, 300)}`);
  }
  return ok;
}

// Expected responses are not counted in http_req_failed (e.g. 429 from the bulkhead, 403 in the security tests).
export function expect(...codes) {
  return http.expectedStatuses(...codes);
}

// --- Test data, generated in memory ----------------------------------------------------------------------------------
let seq = 0;
// Unique per-VU suffix (also works in setup(), where __ITER does not exist).
export function uniq() { seq++; return `${__VU}-${seq}-${Math.random().toString(36).slice(2, 7)}`; }
export function rnd(n) { return Math.floor(Math.random() * n); }
export function pick(arr) { return arr[rnd(arr.length)]; }

export function bytes(size, seed) {
  const buf = new Uint8Array(size);
  let x = (seed || rnd(1 << 30)) | 1;
  for (let i = 0; i < size; i++) { x ^= x << 13; x ^= x >>> 17; x ^= x << 5; buf[i] = x & 0xff; }
  return buf.buffer;
}

export function csv(size) {
  const head = 'ts,statie,eveniment,durata_min\n';
  let out = head;
  let i = 0;
  while (out.length < size) {
    out += `2026-11-14T0${i % 10}:${String(i % 60).padStart(2, '0')}:00Z,ST-K6-${i % 97},${pick(['declansare', 'scurtcircuit', 'cadere-tensiune'])},${rnd(240)}\n`;
    i++;
  }
  return out;
}

export function sha256hex(data) { return crypto.sha256(data, 'hex'); }

export function meterId(prefix, zone, n) {
  // max 64 chars (contract); e.g. k6-lt3q9a-MUN-0000042
  return `k6-${prefix}-${ZONE_CODES[zone]}-${String(n).padStart(7, '0')}`;
}

// A reading batch like the simulator's: n readings 15 minutes apart, starting at startMs.
export function meterBatch(id, zone, startMs, n) {
  const readings = [];
  for (let i = 0; i < n; i++) {
    readings.push({ ts: new Date(startMs + i * 15 * 60 * 1000).toISOString().replace('.000Z', 'Z'), kwh: Math.round(Math.random() * 800) / 1000 });
  }
  const body = JSON.stringify({ meterId: id, zone, readings });
  return { body, key: `${id}_${readings[0].ts}`, count: n };
}

// --- API operations ---------------------------------------------------------------------------------------------------
const JSON_H = { 'Content-Type': 'application/json' };

export function ingest(user, batch, scenario, expected) {
  const res = http.post(`${METER_URL}/meter-readings`, batch.body, {
    headers: auth(user, Object.assign({ 'Idempotency-Key': batch.key, 'X-Scenario': scenario }, JSON_H)),
    tags: { op: 'meter_ingest', name: 'POST /meter-readings' },
    responseCallback: expect(200, 201, 429),
  });
  track(res, expected || [200, 201, 429], 'meter_ingest', 'METER_READING');
  if (res.status === 200) meterDuplicates.add(1);
  return res;
}

// Like the simulator: on 429/503 wait for Retry-After (seconds) before the next send.
export function honorRetryAfter(res, max) {
  if (res.status !== 429 && res.status !== 503) return;
  const s = Number(res.headers['Retry-After'] || 1);
  sleep(Math.min(isNaN(s) ? 1 : s, max || 5) * (0.5 + Math.random() / 2));
}

export function uploadOutage(user, scenario, size) {
  const name = `k6-${RUN_ID}-avarie-${uniq()}.csv`;
  const content = csv(size || 2048);
  const zone = pick(ZONES);
  const res = http.post(`${BASE_URL}/files`, {
    category: 'OUTAGE_RECORD',
    zone,
    occurredAt: new Date().toISOString(),
    stationId: `ST-K6-${rnd(1000)}`,
    file: http.file(content, name, 'text/csv'),
  }, {
    headers: auth(user, { 'X-Scenario': scenario }),
    tags: { op: 'outage_upload', name: 'POST /files (OUTAGE_RECORD)' },
    responseCallback: expect(201, 429),
  });
  const ok = track(res, [201, 429], 'outage_upload', 'OUTAGE_RECORD');
  if (res.status === 201) {
    check(res, {
      'outage: correct sha256': (r) => r.json('sha256') === sha256hex(content),
      'outage: storage S3 (4.2)': (r) => r.json('storage') === 'S3',
      'outage: CONFIRMED': (r) => r.json('status') === 'CONFIRMED',
    });
  }
  return { res, ok, name, content };
}

export function uploadDoc(user, scenario, size) {
  const name = `k6-${RUN_ID}-racordare-${uniq()}.pdf`;
  const data = bytes(size || 4096);
  const res = http.post(`${BASE_URL}/files`, {
    category: 'CONNECTION_DOC',
    file: http.file(data, name, 'application/pdf'),
  }, {
    headers: auth(user, { 'X-Scenario': scenario }),
    tags: { op: 'doc_upload', name: 'POST /files (CONNECTION_DOC)' },
    responseCallback: expect(201, 429),
  });
  const ok = track(res, [201, 429], 'doc_upload', 'CONNECTION_DOC');
  if (res.status === 201) {
    check(res, {
      'document: encrypted': (r) => r.json('encrypted') === true,
      'document: sha256Plain = original': (r) => r.json('sha256Plain') === sha256hex(data),
      'document: storage DB (4.2)': (r) => r.json('storage') === 'DB',
    });
  }
  return { res, ok, name, data };
}

// Chunked upload (small video). chunkSize comes from the policy (16 MiB), so a small file is a single chunk.
export function uploadVideo(user, scenario, size) {
  const name = `k6-${RUN_ID}-inspectie-${uniq()}.mp4`;
  const total = size || 256 * 1024;
  const data = bytes(total);
  const init = http.post(`${BASE_URL}/uploads`, JSON.stringify({ category: 'INSPECTION_VIDEO', fileName: name, totalSize: total }), {
    headers: auth(user, Object.assign({ 'X-Scenario': scenario }, JSON_H)),
    tags: { op: 'video_init', name: 'POST /uploads' },
    responseCallback: expect(201, 429),
  });
  if (!track(init, [201, 429], 'video_init', 'INSPECTION_VIDEO') || init.status !== 201) return { res: init, ok: init.status === 429 };
  const s = init.json();
  const chunkSize = s.chunkSize;
  let n = 1;
  for (let off = 0; off < total; off += chunkSize, n++) {
    const part = data.slice(off, Math.min(off + chunkSize, total));
    const put = http.put(`${BASE_URL}/uploads/${s.uploadId}/chunks/${n}`, part, {
      headers: auth(user, { 'Content-Type': 'application/octet-stream' }),
      tags: { op: 'video_chunk', name: 'PUT /uploads/{id}/chunks/{n}' },
      responseCallback: expect(200, 429),
    });
    if (!track(put, [200], 'video_chunk', 'INSPECTION_VIDEO')) return { res: put, ok: false };
    check(put, { 'video: correct chunk sha': (r) => r.json('sha256') === sha256hex(part) });
  }
  // resending the last chunk must be idempotent (200, duplicate: true)
  const lastN = n - 1;
  const lastPart = data.slice((lastN - 1) * chunkSize, total);
  const dup = http.put(`${BASE_URL}/uploads/${s.uploadId}/chunks/${lastN}`, lastPart, {
    headers: auth(user, { 'Content-Type': 'application/octet-stream' }),
    tags: { op: 'video_chunk', name: 'PUT /uploads/{id}/chunks/{n}' },
  });
  track(dup, [200], 'video_chunk', 'INSPECTION_VIDEO');
  check(dup, { 'video: resent chunk is a duplicate': (r) => r.status === 200 && r.json('duplicate') === true });

  const done = http.post(`${BASE_URL}/uploads/${s.uploadId}/complete`, null, {
    headers: auth(user),
    tags: { op: 'video_complete', name: 'POST /uploads/{id}/complete' },
    responseCallback: expect(200, 201, 429),
  });
  const ok = track(done, [200, 201], 'video_complete', 'INSPECTION_VIDEO');
  if (ok) {
    check(done, {
      'video: correct final sha256': (r) => r.json('sha256') === sha256hex(data),
      'video: storage FTP (4.2)': (r) => r.json('storage') === 'FTP',
    });
  }
  return { res: done, ok, name, data };
}

export function search(user, params, expected) {
  const qs = Object.keys(params || {}).map((k) => `${k}=${encodeURIComponent(params[k])}`).join('&');
  const res = http.get(`${BASE_URL}/files${qs ? '?' + qs : ''}`, {
    headers: auth(user),
    tags: { op: 'search', name: 'GET /files' },
    responseCallback: expect(...(expected || [200])),
  });
  track(res, expected || [200], 'search');
  return res;
}

// Direct download; checks X-Content-SHA256 against the received bytes.
export function download(user, id, range) {
  const headers = auth(user);
  if (range) headers.Range = range;
  const res = http.get(`${BASE_URL}/files/${id}/download`, {
    headers, responseType: 'binary',
    tags: { op: 'download', name: 'GET /files/{id}/download' },
    responseCallback: expect(200, 206, 429),
  });
  const ok = track(res, range ? [206, 200, 429] : [200, 429], 'download');
  if (res.status === 200) {
    const good = res.headers['X-Content-Sha256'] === sha256hex(res.body);
    if (!good) integrityFail.add(1);
    check(res, { 'download: X-Content-SHA256 = body hash': () => good });
  }
  return { res, ok };
}

export function verify(user, id, deep) {
  const res = http.get(`${BASE_URL}/files/${id}/verify${deep ? '?deep=true' : ''}`, {
    headers: auth(user),
    tags: { op: 'verify', name: 'GET /files/{id}/verify' },
    responseCallback: expect(200, 429),
  });
  const ok = track(res, [200, 429], 'verify');
  if (res.status === 200) check(res, { 'verify: ok=true': (r) => r.json('ok') === true });
  return { res, ok };
}

export function downloadLink(user, id) {
  const res = http.post(`${BASE_URL}/files/${id}/download-link`, null, {
    headers: auth(user), tags: { op: 'download_link', name: 'POST /files/{id}/download-link' },
  });
  track(res, [200], 'download_link');
  if (res.status !== 200) return { res, ok: false };
  const url = res.json('url'); // relative path /api/v1/downloads/{token}
  const origin = BASE_URL.replace(/\/api\/v1$/, '');
  const dl = http.get(`${origin}${url}`, {
    responseType: 'binary', tags: { op: 'download_token', name: 'GET /downloads/{token}' },
  });
  const ok = track(dl, [200], 'download_token');
  if (ok) check(dl, { 'signed link: X-Content-SHA256 = body hash': (r) => r.headers['X-Content-Sha256'] === sha256hex(r.body) });
  return { res: dl, ok };
}

// Response shape checked against the contract: all have `interpretation`, except Requirements (4 requirements R1-R4).
// /stats/peak-vs-normal and /stats/storage-advisor may return 503 when Prometheus / live data is unavailable.
const hasInterpretation = { label: 'has interpretation', fn: (r) => typeof r.json('interpretation') === 'string' };
export const STATS = [
  { path: '/stats/outage-zones', ok: [200], shape: hasInterpretation },
  { path: '/stats/anomalies', ok: [200], shape: hasInterpretation },
  { path: '/stats/requirements', ok: [200], shape: { label: '4 requirements R1-R4 with status', fn: (r) => {
    const reqs = r.json('requirements');
    return Array.isArray(reqs) && reqs.length === 4 && reqs.every((q) => q.status === 'OK' || q.status === 'AT_RISK');
  } } },
  { path: '/stats/peak-vs-normal', ok: [200, 503], shape: hasInterpretation },
  { path: '/stats/storage-advisor', ok: [200, 503], shape: hasInterpretation },
];

export function stats(user, s) {
  const res = http.get(`${BASE_URL}${s.path}`, {
    headers: auth(user), tags: { op: 'stats', name: `GET ${s.path}` },
    responseCallback: expect(...s.ok),
  });
  track(res, s.ok, 'stats');
  if (res.status === 200) check(res, { [`${s.path}: ${s.shape.label}`]: s.shape.fn });
  return res;
}

export { http, check };
