// Upload mare (F8, planul echipei): un video de 1,1 GB prin upload in bucati (/uploads/*), rulat la cerere.
// Continutul se genereaza bucata cu bucata in memorie (un buffer de baza de chunkSize, cu numarul bucatii scris la
// inceput), iar SHA-256 se calculeaza incremental, deci k6 nu tine niciodata 1,1 GB. La final se compara hash-ul
// local cu cel din raspunsul `complete` si cu /verify, plus un Range de control.
//   BIG_SIZE_MB -> marimea in MiB (implicit 1100 = 1,1 GB)
// Contractul nu are DELETE pentru fisiere: id-ul fisierului creat se afiseaza in rezumat, ca sa poata fi sters.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { BASE_URL, RUN_ID, auth, track, sha256hex } from './lib/common.js';
import { Trend } from 'k6/metrics';
import { makeSummary } from './lib/summary.js';

const uploadTime = new Trend('filegrid_big_upload_s');

const SIZE = Number(__ENV.BIG_SIZE_MB || 1100) * 1024 * 1024;
const LONG = '600s';

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  vus: 1,
  iterations: 1,
  setupTimeout: '60s',
  teardownTimeout: '60s',
  thresholds: {
    checks: ['rate==1'],
    filegrid_unexpected: ['rate==0'],
    'http_req_duration{op:video_chunk}': ['p(95)<15000'],
  },
};


function baseBuffer(size) {
  const buf = new Uint8Array(size);
  let x = 0x9e3779b9 | 0;
  // 32 de biti o data: xorshift scris pe 4 octeti
  for (let i = 0; i + 3 < size; i += 4) {
    x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
    buf[i] = x & 0xff; buf[i + 1] = (x >>> 8) & 0xff; buf[i + 2] = (x >>> 16) & 0xff; buf[i + 3] = (x >>> 24) & 0xff;
  }
  return buf;
}

export default function () {
  const name = `k6-${RUN_ID}-inspectie-mare.mp4`;
  const init = http.post(`${BASE_URL}/uploads`, JSON.stringify({ category: 'INSPECTION_VIDEO', fileName: name, totalSize: SIZE }), {
    headers: auth('teren', { 'Content-Type': 'application/json', 'X-Scenario': 'normal' }),
    tags: { op: 'video_init', name: 'POST /uploads' },
  });
  track(init, [201], 'video_init');
  if (!check(init, { 'init 201': (r) => r.status === 201 })) return;
  const s = init.json();
  console.log(`upload ${s.uploadId}: ${SIZE} octeti, ${s.totalChunks} bucati de ${s.chunkSize}, storage ${s.storage}`);

  const base = baseBuffer(s.chunkSize);
  const hasher = crypto.createHash('sha256');
  const t0 = Date.now();
  let sent = 0;
  for (let n = 1; n <= s.totalChunks; n++) {
    const len = Math.min(s.chunkSize, SIZE - sent);
    const chunk = new Uint8Array(len);
    chunk.set(len === base.length ? base : base.subarray(0, len));
    // bucati diferite intre ele: numarul bucatii in primii 4 octeti
    chunk[0] = n & 0xff; chunk[1] = (n >>> 8) & 0xff; chunk[2] = 0x6b; chunk[3] = 0x36;
    hasher.update(chunk.buffer);
    let put;
    for (let attempt = 1; attempt <= 5; attempt++) {
      put = http.put(`${BASE_URL}/uploads/${s.uploadId}/chunks/${n}`, chunk.buffer, {
        headers: auth('teren', { 'Content-Type': 'application/octet-stream' }),
        tags: { op: 'video_chunk', name: 'PUT /uploads/{id}/chunks/{n}' }, timeout: LONG,
        responseCallback: http.expectedStatuses(200, 409, 429, 503),
      });
      if (put.status === 200) break;
      console.warn(`bucata ${n}: ${put.status}, reincercare ${attempt}`);
    }
    track(put, [200], 'video_chunk');
    if (!check(put, { 'bucata confirmata': (r) => r.status === 200 })) return;
    sent += len;
    if (n % 10 === 0 || n === s.totalChunks) console.log(`bucata ${n}/${s.totalChunks} (${Math.round(sent / 1048576)} MiB)`);
  }
  const localSha = hasher.digest('hex');

  const done = http.post(`${BASE_URL}/uploads/${s.uploadId}/complete`, null, {
    headers: auth('teren'), tags: { op: 'video_complete', name: 'POST /uploads/{id}/complete' }, timeout: LONG,
  });
  const uploadSecs = (Date.now() - t0) / 1000;
  uploadTime.add(uploadSecs);
  track(done, [200, 201], 'video_complete');
  check(done, {
    'complete 201': (r) => r.status === 201 || r.status === 200,
    'sha256 server = sha256 local': (r) => r.json('sha256') === localSha,
    'marime corecta': (r) => r.json('size') === SIZE,
  });
  if (done.status !== 201 && done.status !== 200) return;
  const fileId = done.json('id');
  console.log(`fisier ${fileId} pe ${done.json('storage')}, sha256 ${localSha}, upload ${uploadSecs.toFixed(1)} s`);

  const v = http.get(`${BASE_URL}/files/${fileId}/verify`, {
    headers: auth('teren'), tags: { op: 'verify', name: 'GET /files/{id}/verify' }, timeout: LONG,
  });
  track(v, [200], 'verify');
  check(v, { 'verify 200 ok': (r) => r.status === 200 && r.json('ok') === true });

  // Range de control pe ultimii 1024 de octeti (fara sa descarcam 1,1 GB)
  const r = http.get(`${BASE_URL}/files/${fileId}/download`, {
    headers: auth('teren', { Range: `bytes=${SIZE - 1024}-` }), responseType: 'binary',
    tags: { op: 'download', name: 'GET /files/{id}/download' }, timeout: LONG,
  });
  track(r, [206], 'download');
  const lastLen = SIZE - (s.totalChunks - 1) * s.chunkSize;
  const expectedTail = base.subarray(lastLen - 1024, lastLen);
  check(r, {
    'Range final -> 206': (x) => x.status === 206,
    'ultimii 1024 octeti corecti': (x) => x.status === 206 && sha256hex(x.body) === sha256hex(expectedTail.slice().buffer),
  });
}

// handleSummary nu vede variabilele VU-ului: durata vine din metrica, id-ul fisierului din logul de mai sus.
export const handleSummary = makeSummary('big-upload', (data) => {
  const t = data.metrics.filegrid_big_upload_s ? data.metrics.filegrid_big_upload_s.values.max : 0;
  return [
    `Upload mare: ${Math.round(SIZE / 1048576)} MiB in ${t.toFixed(1)} s` + (t ? ` (${(SIZE / 1048576 / t).toFixed(1)} MiB/s)` : ''),
    'Fisierul creat: vezi linia "fisier <id>" din log (contractul nu are DELETE; se sterge manual).',
  ];
});
