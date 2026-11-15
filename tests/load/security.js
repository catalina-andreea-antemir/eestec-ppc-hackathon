// Securitate (F8, C10): matricea de roluri 4.4. Fiecare rol care incearca o operatie nepermisa primeste 403,
// cererile fara token sau cu token nepotrivit primesc 401. O singura iteratie; toate verificarile trebuie sa treaca.
import { group } from 'k6';
import {
  BASE_URL, RUN_ID, ZONES, auth, track, forbiddenOk, uploadOutage, uploadDoc, meterBatch, meterId, http, check, csv, bytes,
} from './lib/common.js';
import { makeSummary } from './lib/summary.js';

export const options = {
  vus: 1,
  iterations: 1,
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'],
    filegrid_unexpected: ['rate==0'],
    filegrid_forbidden_403: ['count>=20'],
  },
};

export function setup() {
  const outage = uploadOutage('teren', 'k6-security', 1024);
  const doc = uploadDoc('racordari', 'k6-security', 2048);
  return {
    outageId: outage.res.status === 201 ? outage.res.json('id') : null,
    docId: doc.res.status === 201 ? doc.res.json('id') : null,
  };
}

function req(user, method, path, body, headers) {
  const h = user ? auth(user, headers) : Object.assign({}, headers || {});
  return http.request(method, `${BASE_URL}${path}`, body === undefined ? null : body, {
    headers: h,
    tags: { op: 'security', name: `${method} ${path.replace(/[0-9a-f-]{36}/g, '{id}').replace(/\?.*/, '')}` },
    responseCallback: http.expectedStatuses(200, 401, 403),
  });
}

function deny(user, role, method, path, body, headers) {
  const res = req(user, method, path, body, headers);
  if (track(res, [403], 'security')) forbiddenOk.add(1, { role });
  check(res, { [`${role} ${method} ${path.replace(/[0-9a-f-]{36}/g, '{id}')} -> 403`]: (r) => r.status === 403 && r.json('code') === 'FORBIDDEN' });
}

function unauthorized(label, method, path, body, headers) {
  const res = req(null, method, path, body, headers);
  track(res, [401], 'security');
  check(res, { [`${label} -> 401`]: (r) => r.status === 401 });
}

const outageForm = () => ({ category: 'OUTAGE_RECORD', zone: 'Banat', file: http.file(csv(300), `k6-${RUN_ID}-interzis.csv`, 'text/csv') });
const docForm = () => ({ category: 'CONNECTION_DOC', file: http.file(bytes(300), `k6-${RUN_ID}-interzis.pdf`, 'application/pdf') });
const videoInit = JSON.stringify({ category: 'INSPECTION_VIDEO', fileName: `k6-${RUN_ID}-interzis.mp4`, totalSize: 1024 });
const JSON_H = { 'Content-Type': 'application/json' };

export default function (d) {
  const b = meterBatch(meterId(`${RUN_ID}x`, ZONES[1], 1), ZONES[1], Date.UTC(2026, 0, 1), 4);
  const meterH = Object.assign({ 'Idempotency-Key': b.key }, JSON_H);

  group('DISPATCH: nu incarca, nu vede documente', () => {
    deny('dispecer', 'DISPATCH', 'POST', '/files', outageForm());
    deny('dispecer', 'DISPATCH', 'POST', '/uploads', videoInit, JSON_H);
    deny('dispecer', 'DISPATCH', 'POST', '/meter-readings', b.body, meterH);
    deny('dispecer', 'DISPATCH', 'GET', '/files?category=CONNECTION_DOC');
    deny('dispecer', 'DISPATCH', 'GET', '/files?category=INSPECTION_VIDEO');
    if (d.docId) {
      deny('dispecer', 'DISPATCH', 'GET', `/files/${d.docId}`);
      deny('dispecer', 'DISPATCH', 'GET', `/files/${d.docId}/download`);
      deny('dispecer', 'DISPATCH', 'POST', `/files/${d.docId}/download-link`);
    }
    deny('dispecer', 'DISPATCH', 'GET', '/admin/health');
  });

  group('FIELD: fara documente, statistici, admin, citiri', () => {
    deny('teren', 'FIELD', 'POST', '/files', docForm());
    deny('teren', 'FIELD', 'GET', '/files?category=CONNECTION_DOC');
    deny('teren', 'FIELD', 'GET', '/files?category=METER_READING');
    if (d.docId) deny('teren', 'FIELD', 'GET', `/files/${d.docId}/verify?deep=true`);
    deny('teren', 'FIELD', 'GET', '/stats/anomalies');
    deny('teren', 'FIELD', 'GET', '/stats/outage-zones');
    deny('teren', 'FIELD', 'POST', '/meter-readings', b.body, meterH);
    deny('teren', 'FIELD', 'GET', '/admin/health');
  });

  group('CONNECTIONS: doar documente', () => {
    deny('racordari', 'CONNECTIONS', 'POST', '/files', outageForm());
    deny('racordari', 'CONNECTIONS', 'POST', '/uploads', videoInit, JSON_H);
    deny('racordari', 'CONNECTIONS', 'GET', '/files?category=OUTAGE_RECORD');
    if (d.outageId) {
      deny('racordari', 'CONNECTIONS', 'GET', `/files/${d.outageId}`);
      deny('racordari', 'CONNECTIONS', 'GET', `/files/${d.outageId}/download`);
    }
    deny('racordari', 'CONNECTIONS', 'GET', '/stats/anomalies');
  });

  group('METER_SERVICE: doar POST /meter-readings', () => {
    deny('contor', 'METER_SERVICE', 'GET', '/files');
    deny('contor', 'METER_SERVICE', 'POST', '/files', outageForm());
    deny('contor', 'METER_SERVICE', 'POST', '/uploads', videoInit, JSON_H);
    if (d.outageId) deny('contor', 'METER_SERVICE', 'GET', `/files/${d.outageId}/download`);
    deny('contor', 'METER_SERVICE', 'GET', '/stats/outage-zones');
    deny('contor', 'METER_SERVICE', 'GET', '/admin/health');
  });

  group('401: fara token, token nepotrivit, link semnat modificat', () => {
    unauthorized('GET /files fara token', 'GET', '/files');
    unauthorized('POST /meter-readings fara token', 'POST', '/meter-readings', b.body, meterH);
    unauthorized('GET /files cu token invalid', 'GET', '/files', undefined, { Authorization: 'Bearer abc.def.ghi' });
    const login = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ username: 'dispecer', password: 'k6-parola-gresita' }), {
      headers: JSON_H, tags: { op: 'security', name: 'POST /auth/login (parola gresita)' }, responseCallback: http.expectedStatuses(401),
    });
    track(login, [401], 'security');
    check(login, { 'login cu parola gresita -> 401': (r) => r.status === 401 });
    unauthorized('GET /downloads/{token} modificat', 'GET', '/downloads/k6-token-invalid.AAAA');
  });

  group('ADMIN: acces la tot (control pozitiv)', () => {
    const h = req('admin', 'GET', '/admin/health');
    track(h, [200], 'security_admin');
    check(h, { 'ADMIN GET /admin/health -> 200': (r) => r.status === 200 });
    if (d.docId) {
      const v = req('admin', 'GET', `/files/${d.docId}/verify?deep=true`);
      track(v, [200], 'security_admin');
      check(v, { 'ADMIN verify deep document -> 200': (r) => r.status === 200 });
    }
  });
}

export const handleSummary = makeSummary('security');
