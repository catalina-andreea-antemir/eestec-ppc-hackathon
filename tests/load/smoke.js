// Smoke (F8): fiecare rol se autentifica si face fluxul permis de matricea 4.4. O singura iteratie; totul trebuie sa treaca.
import { group } from 'k6';
import {
  RUN_ID, USERS, ZONES, login, ingest, meterBatch, meterId, uploadOutage, uploadDoc, uploadVideo,
  search, download, verify, downloadLink, stats, STATS, http, check, BASE_URL, auth, track,
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
    'http_req_duration{op:search}': ['p(95)<1000'],
    'http_req_duration{op:outage_upload}': ['p(95)<2000'],
  },
};

const SCEN = 'k6-smoke';

export default function () {
  for (const u of Object.keys(USERS)) {
    group(`login ${u}`, () => {
      const t = login(u);
      check(t, { [`${u}: accessToken primit`]: (v) => !!v });
    });
  }

  let outageId;
  group('teren (FIELD): avarie + video + cautare/descarcare/verify', () => {
    const up = uploadOutage('teren', SCEN, 4096);
    check(up.res, { 'teren: upload avarie 201': (r) => r.status === 201 });
    outageId = up.res.status === 201 ? up.res.json('id') : null;

    const vid = uploadVideo('teren', SCEN, 300 * 1024);
    check(vid.res, { 'teren: upload video chunked confirmat': () => vid.ok });
    const videoId = vid.ok ? vid.res.json('id') : null;

    const s = search('teren', { q: `k6-${RUN_ID}`, size: 50 });
    check(s, {
      'teren: cautarea gaseste avaria k6': (r) => r.status === 200 && r.json('items').some((f) => f.id === outageId),
      'teren: cautarea gaseste video-ul k6': (r) => r.status === 200 && r.json('items').some((f) => f.id === videoId),
    });
    if (outageId) {
      check(download('teren', outageId).res, { 'teren: download avarie 200': (r) => r.status === 200 });
      const part = download('teren', outageId, 'bytes=10-99');
      check(part.res, {
        'teren: Range -> 206': (r) => r.status === 206,
        'teren: 206 are 90 octeti': (r) => r.body && r.body.byteLength === 90,
      });
      check(verify('teren', outageId).res, { 'teren: verify avarie 200': (r) => r.status === 200 });
      check(downloadLink('teren', outageId).res, { 'teren: link semnat 200': (r) => r.status === 200 });
    }
    if (videoId) {
      check(download('teren', videoId).res, { 'teren: download video 200': (r) => r.status === 200 });
      check(verify('teren', videoId).res, { 'teren: verify video 200': (r) => r.status === 200 });
    }
  });

  group('racordari (CONNECTIONS): document criptat', () => {
    const up = uploadDoc('racordari', SCEN, 8192);
    check(up.res, { 'racordari: upload document 201': (r) => r.status === 201 });
    if (up.res.status !== 201) return;
    const id = up.res.json('id');
    const s = search('racordari', { category: 'CONNECTION_DOC', size: 100 });
    check(s, { 'racordari: documentul apare in cautare': (r) => r.status === 200 && r.json('items').some((f) => f.id === id) });
    const d = download('racordari', id);
    check(d.res, {
      'racordari: download 200 (decriptat)': (r) => r.status === 200,
      'racordari: X-Content-SHA256 = sha256Plain': (r) => r.headers['X-Content-Sha256'] === up.res.json('sha256Plain'),
    });
    const ranged = download('racordari', id, 'bytes=0-9');
    check(ranged.res, { 'racordari: Range ignorat la CONNECTION_DOC (200)': (r) => r.status === 200 });
    const v = verify('racordari', id, true);
    check(v.res, {
      'racordari: verify deep 200': (r) => r.status === 200,
      'racordari: verify deep compara sha256Plain': (r) => r.status === 200 && r.json('actualSha256Plain') === up.res.json('sha256Plain'),
    });
  });

  group('contor (METER_SERVICE): lot de citiri + duplicat', () => {
    const id = meterId(`${RUN_ID}s`, ZONES[0], 1);
    const b = meterBatch(id, ZONES[0], Date.UTC(2026, 0, 1), 96);
    const first = ingest('contor', b, SCEN, [201]);
    check(first, {
      'contor: lot nou 201': (r) => r.status === 201,
      'contor: readingsCount 96': (r) => r.status === 201 && r.json('readingsCount') === 96,
    });
    const again = ingest('contor', b, SCEN, [200]);
    check(again, {
      'contor: acelasi lot -> 200 duplicate': (r) => r.status === 200 && r.json('duplicate') === true,
      'contor: duplicatul are acelasi fileId': (r) => r.status === 200 && first.status === 201 && r.json('fileId') === first.json('fileId'),
    });
    const bad = http.post(`${BASE_URL}/meter-readings`, b.body, {
      headers: auth('contor', { 'Content-Type': 'application/json', 'Idempotency-Key': `${id}_gresit` }),
      tags: { op: 'meter_ingest_bad', name: 'POST /meter-readings (cheie gresita)' },
      responseCallback: http.expectedStatuses(400),
    });
    track(bad, [400], 'meter_ingest_bad');
    check(bad, { 'contor: Idempotency-Key gresit -> 400 IDEMPOTENCY_KEY_MISMATCH': (r) => r.status === 400 && r.json('code') === 'IDEMPOTENCY_KEY_MISMATCH' });
  });

  group('dispecer (DISPATCH): cautare, descarcare avarie, statistici', () => {
    const s = search('dispecer', { category: 'OUTAGE_RECORD', q: `k6-${RUN_ID}` });
    check(s, { 'dispecer: vede avaria k6': (r) => r.status === 200 && r.json('items').some((f) => f.id === outageId) });
    const m = search('dispecer', { category: 'METER_READING', size: 5 });
    check(m, { 'dispecer: poate cauta METER_READING': (r) => r.status === 200 });
    if (outageId) {
      check(download('dispecer', outageId).res, { 'dispecer: download avarie 200': (r) => r.status === 200 });
      check(verify('dispecer', outageId).res, { 'dispecer: verify avarie 200': (r) => r.status === 200 });
    }
    for (const st of STATS) {
      const r = stats('dispecer', st);
      check(r, { [`dispecer: ${st.path} -> ${st.ok.join('/')}`]: (x) => st.ok.indexOf(x.status) >= 0 });
    }
  });

  group('admin (ADMIN): health storage + cautare totala', () => {
    const h = http.get(`${BASE_URL}/admin/health`, { headers: auth('admin'), tags: { op: 'admin_health', name: 'GET /admin/health' } });
    track(h, [200], 'admin_health');
    check(h, { 'admin: /admin/health 200': (r) => r.status === 200 });
    const s = search('admin', { q: `k6-${RUN_ID}`, size: 100 });
    check(s, { 'admin: vede fisierele k6 din toate categoriile': (r) => r.status === 200 && r.json('totalItems') >= 2 });
  });
}

export const handleSummary = makeSummary('smoke');
