import { HttpEvent, HttpRequest } from '@angular/common/http';
import { from, Observable, switchMap } from 'rxjs';

import { Role } from '../auth/auth.models';
import { Category, DownloadLink, FileMetadata, FilePage, searchableCategories, VerifyResult } from '../files/files.models';
import { StorageType, Zone, ZONES } from '../upload/upload.models';
import { apiError, nowIso, respond } from './mock-uploads';
import { Sha256, sha256Hex } from './sha256';

/**
 * Mock for search, details, verification and download (GET /files, GET /files/{id},
 * GET /files/{id}/verify, POST /files/{id}/download-link), per the contract.
 *
 * - Holds files uploaded from the Upload page (metadata in sessionStorage, content only in
 *   memory: after a refresh uploaded files are still listed but can no longer be downloaded/verified)
 *   plus ~45 "seed" files generated on the first request, so paging has something to show.
 * - Two seed files are deliberately corrupt (the stored content no longer matches the metadata hash),
 *   so "Verify integrity" can show the red state too: `avarie-ST-TIMISOARA-007-CORUPT.csv` and
 *   `contract-racordare-CORUPT.pdf`.
 * - Download: the link is a `blob:` URL of the in-memory content, valid for 15 minutes
 *   (then the URL is revoked, the equivalent of a 401 "link expired"). The browser downloads it natively,
 *   like the real backend's `/api/v1/downloads/{token}`, and the UI code is the same. `blob:` was chosen
 *   because `ng serve` cannot serve an endpoint of its own, and without a backend the proxy would fail.
 */

interface StoredFile {
  meta: FileMetadata;
  /** The "stored" bytes (for documents: the original; encryption is only simulated in the hash). null = lost on refresh. */
  content: Blob | null;
}

const UPLOADED_KEY = 'filegrid.mock.files';
const LINK_TTL_MS = 15 * 60 * 1000;
const STORED_ENCRYPTION_PREFIX = 'aes-gcm:';

/** Content of the files uploaded in the current session (memory only). */
const uploadedContent = new Map<string, Blob>();
let seedPromise: Promise<StoredFile[]> | null = null;

/** Called by the upload mock after POST /files and POST /uploads/{id}/complete. */
export function registerMockFile(meta: FileMetadata, content: Blob | null): void {
  if (content) {
    uploadedContent.set(meta.id, content);
  }
  const all = loadUploaded();
  all[meta.id] = meta;
  try {
    sessionStorage.setItem(UPLOADED_KEY, JSON.stringify(all));
  } catch {
    /* ignored */
  }
}

/** Metadata hash for the "stored" bytes: for encrypted documents we mimic the ciphertext hash. */
export async function storedSha256(plainSha256: string, encrypted: boolean): Promise<string> {
  return encrypted ? sha256Hex(new TextEncoder().encode(STORED_ENCRYPTION_PREFIX + plainSha256)) : plainSha256;
}

export function handleFileRoutes(
  req: HttpRequest<unknown>,
  path: string,
  user: { sub: string; role: Role },
): Observable<HttpEvent<unknown>> | null {
  if (req.method === 'GET' && path === '/files') {
    return withFiles((files) => search(files, req, user.role));
  }
  const match = /^\/files\/([^/]+)(?:\/(verify|download-link))?$/.exec(path);
  if (!match) {
    return null;
  }
  const id = decodeURIComponent(match[1]);
  const action = match[2];
  const allowed =
    (req.method === 'GET' && !action) ||
    (req.method === 'GET' && action === 'verify') ||
    (req.method === 'POST' && action === 'download-link');
  if (!allowed) {
    return null;
  }
  return withFiles((files) => {
    const file = files.find((f) => f.meta.id === id);
    if (!file) {
      return apiError(404, 'NOT_FOUND', 'Fișierul nu există.');
    }
    if (!searchableCategories(user.role).includes(file.meta.category)) {
      return apiError(403, 'FORBIDDEN', `Rolul ${user.role} nu are acces la categoria ${file.meta.category}.`);
    }
    if (!action) {
      return respond(200, file.meta);
    }
    if (action === 'verify') {
      return verify(file, req.params.get('deep') === 'true');
    }
    return downloadLink(file);
  });
}

// ---------- GET /files ----------

function search(files: StoredFile[], req: HttpRequest<unknown>, role: Role): Observable<HttpEvent<unknown>> {
  const p = req.params;
  const allowed = searchableCategories(role);
  const category = p.get('category') as Category | null;
  if (category && !['METER_READING', 'OUTAGE_RECORD', 'CONNECTION_DOC', 'INSPECTION_VIDEO'].includes(category)) {
    return apiError(400, 'VALIDATION_ERROR', `Categorie necunoscută: ${category}.`);
  }
  if (category && !allowed.includes(category)) {
    return apiError(403, 'FORBIDDEN', `Rolul ${role} nu are acces la categoria ${category}.`);
  }
  const page = Number(p.get('page') ?? 1);
  const size = Number(p.get('size') ?? 20);
  if (!Number.isInteger(page) || page < 1 || !Number.isInteger(size) || size < 1 || size > 100) {
    return apiError(400, 'VALIDATION_ERROR', 'page trebuie să fie ≥ 1, iar size între 1 și 100.');
  }
  const q = (p.get('q') ?? '').trim().toLowerCase();
  if (q.length > 200) {
    return apiError(400, 'VALIDATION_ERROR', 'q are cel mult 200 de caractere.');
  }
  const fromMs = parseInstant(p.get('from'));
  const toMs = parseInstant(p.get('to'));
  if (fromMs === undefined || toMs === undefined) {
    return apiError(400, 'VALIDATION_ERROR', 'from și to trebuie să fie momente ISO-8601.');
  }

  const matches = files
    .map((f) => f.meta)
    .filter((m) => (category ? m.category === category : allowed.includes(m.category)))
    // Document names are encrypted on the server: q does not apply to CONNECTION_DOC.
    .filter((m) => !q || m.category === 'CONNECTION_DOC' || m.originalName.toLowerCase().includes(q))
    .filter((m) => fromMs === null || Date.parse(m.createdAt) >= fromMs)
    .filter((m) => toMs === null || Date.parse(m.createdAt) < toMs)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  const body: FilePage = {
    items: matches.slice((page - 1) * size, page * size),
    page,
    size,
    totalItems: matches.length,
    totalPages: Math.ceil(matches.length / size),
  };
  return respond(200, body);
}

/** null = parameter missing; undefined = invalid. */
function parseInstant(value: string | null): number | null | undefined {
  if (value === null || value === '') {
    return null;
  }
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? undefined : ms;
}

// ---------- GET /files/{id}/verify ----------

function verify(file: StoredFile, deepRequested: boolean): Observable<HttpEvent<unknown>> {
  const { meta, content } = file;
  if (!content) {
    return apiError(404, 'NOT_FOUND', 'Conținutul nu mai e în memoria mock-ului (pagina a fost reîncărcată după încărcare).');
  }
  const deep = deepRequested && meta.category === 'CONNECTION_DOC';
  return from(hashBlob(content)).pipe(
    switchMap(async (plain) => ({ plain, stored: await storedSha256(plain, meta.encrypted) })),
    switchMap(({ plain, stored }) => {
      const plainMismatch = deep && plain !== meta.sha256Plain;
      if (stored !== meta.sha256 || plainMismatch) {
        return apiError(409, 'FILE_CORRUPT', 'fișier corupt', {
          details: {
            fileId: meta.id,
            expectedSha256: meta.sha256,
            actualSha256: stored,
            ...(deep ? { expectedSha256Plain: meta.sha256Plain, actualSha256Plain: plain } : {}),
          },
        });
      }
      const result: VerifyResult = {
        fileId: meta.id,
        ok: true,
        algorithm: 'SHA-256',
        deep,
        expectedSha256: meta.sha256,
        actualSha256: stored,
        expectedSha256Plain: deep ? meta.sha256Plain : null,
        actualSha256Plain: deep ? plain : null,
        verifiedAt: nowIso(),
      };
      return respond(200, result);
    }),
  );
}

/** SHA-256 of a Blob read in chunks (also works for 1 GB videos without loading them whole). */
async function hashBlob(blob: Blob): Promise<string> {
  if (blob.size <= 64 * 1024 * 1024) {
    return sha256Hex(await blob.arrayBuffer());
  }
  const hash = new Sha256();
  const step = 8 * 1024 * 1024;
  for (let offset = 0; offset < blob.size; offset += step) {
    hash.update(new Uint8Array(await blob.slice(offset, offset + step).arrayBuffer()));
  }
  return hash.hex();
}

// ---------- POST /files/{id}/download-link ----------

function downloadLink(file: StoredFile): Observable<HttpEvent<unknown>> {
  if (!file.content) {
    return apiError(404, 'NOT_FOUND', 'Conținutul nu mai e în memoria mock-ului (pagina a fost reîncărcată după încărcare).');
  }
  const typed = file.content.type ? file.content : new Blob([file.content], { type: file.meta.contentType });
  const url = URL.createObjectURL(typed);
  // As on the server: the link stops working after 15 minutes.
  setTimeout(() => URL.revokeObjectURL(url), LINK_TTL_MS);
  const body: DownloadLink = { url, expiresAt: new Date(Date.now() + LINK_TTL_MS).toISOString().replace(/\.\d{3}Z$/, 'Z') };
  return respond(200, body);
}

// ---------- files: seed + uploaded ----------

function withFiles(handler: (files: StoredFile[]) => Observable<HttpEvent<unknown>>): Observable<HttpEvent<unknown>> {
  seedPromise ??= buildSeed();
  return from(seedPromise).pipe(
    switchMap((seed) => {
      const uploaded = Object.values(loadUploaded()).map((meta) => ({ meta, content: uploadedContent.get(meta.id) ?? null }));
      return handler([...uploaded, ...seed]);
    }),
  );
}

function loadUploaded(): Record<string, FileMetadata> {
  try {
    return JSON.parse(sessionStorage.getItem(UPLOADED_KEY) ?? '{}') as Record<string, FileMetadata>;
  } catch {
    return {};
  }
}

interface SeedSpec {
  category: Category;
  name: string;
  contentType: string;
  storage: StorageType;
  createdBy: string;
  hoursAgo: number;
  content: Uint8Array;
  corrupt?: boolean;
  zone?: Zone;
  stationId?: string;
}

const STATIONS = ['ST-BRASOV-114', 'ST-CONSTANTA-020', 'ST-TIMISOARA-007', 'ST-PLOIESTI-031', 'ST-ARAD-012', 'ST-TULCEA-004'];
const METERS = ['RO-MUN-0004821', 'RO-BAN-0001377', 'RO-DOB-0000412', 'RO-MUN-0019930', 'RO-BAN-0007712'];
const DOCS = ['cerere-racordare', 'aviz-tehnic-racordare', 'contract-racordare', 'certificat-racordare', 'plan-situatie'];

/** Seed files: identical on every start (pseudo-random generator with a fixed seed). */
async function buildSeed(): Promise<StoredFile[]> {
  const rand = mulberry32(20261114);
  const text = (value: string) => new TextEncoder().encode(value);
  const specs: SeedSpec[] = [];

  for (let i = 0; i < 18; i++) {
    const station = STATIONS[i % STATIONS.length];
    const zone = ZONES[i % ZONES.length];
    const rows = Array.from({ length: 20 + i }, (_, r) => `${station},${zone},${r},${(rand() * 400).toFixed(1)}`);
    specs.push({
      category: 'OUTAGE_RECORD',
      name: `avarie-${station}-${String(100 + i)}.csv`,
      contentType: 'text/csv',
      storage: i % 5 === 4 ? 'DB' : 'S3',
      createdBy: `teren${(i % 3) + 1}`,
      hoursAgo: 3 + i * 17,
      content: text(`statie,zona,linie,curent_A\n${rows.join('\n')}\n`),
      zone,
      stationId: station,
    });
  }
  for (let i = 0; i < 12; i++) {
    const meter = METERS[i % METERS.length];
    const day = new Date(Date.now() - i * 36 * 3600 * 1000).toISOString().slice(0, 10);
    const readings = Array.from({ length: 24 }, (_, h) => ({ meterId: meter, h, kWh: Number((rand() * 3).toFixed(3)) }));
    specs.push({
      category: 'METER_READING',
      name: `${meter}_${day}.json`,
      contentType: 'application/json',
      storage: i % 4 === 3 ? 'S3' : 'DB',
      createdBy: 'contor1',
      hoursAgo: 1 + i * 36,
      content: text(JSON.stringify(readings)),
    });
  }
  for (let i = 0; i < 8; i++) {
    specs.push({
      category: 'CONNECTION_DOC',
      name: `${DOCS[i % DOCS.length]}-${String(200 + i)}.pdf`,
      contentType: 'application/pdf',
      storage: 'DB',
      createdBy: `racordari${(i % 2) + 1}`,
      hoursAgo: 5 + i * 41,
      content: text(`%PDF-1.4\n% document de racordare fictiv ${i}\n${'x'.repeat(2000 + i * 300)}\n%%EOF\n`),
    });
  }
  for (let i = 0; i < 4; i++) {
    const bytes = new Uint8Array((1 + i) * 1024 * 1024);
    for (let b = 0; b < bytes.length; b += 4096) {
      bytes[b] = Math.floor(rand() * 256);
    }
    specs.push({
      category: 'INSPECTION_VIDEO',
      name: `drona-linie-110kV-tronson-${i + 3}.mp4`,
      contentType: 'video/mp4',
      storage: 'FTP',
      createdBy: `teren${(i % 2) + 1}`,
      hoursAgo: 8 + i * 60,
      content: bytes,
    });
  }
  // The two deliberately corrupt files (for the red "Corrupt" state).
  specs.push({
    category: 'OUTAGE_RECORD',
    name: 'avarie-ST-TIMISOARA-007-CORUPT.csv',
    contentType: 'text/csv',
    storage: 'S3',
    createdBy: 'teren2',
    hoursAgo: 2,
    content: text('statie,zona,linie,curent_A\nST-TIMISOARA-007,Banat,1,312.4\n'),
    corrupt: true,
    zone: 'Banat',
    stationId: 'ST-TIMISOARA-007',
  });
  specs.push({
    category: 'CONNECTION_DOC',
    name: 'contract-racordare-CORUPT.pdf',
    contentType: 'application/pdf',
    storage: 'DB',
    createdBy: 'racordari1',
    hoursAgo: 4,
    content: text('%PDF-1.4\n% document de racordare fictiv, corupt\n%%EOF\n'),
    corrupt: true,
  });

  return Promise.all(specs.map((spec, i) => toStored(spec, i)));
}

async function toStored(spec: SeedSpec, index: number): Promise<StoredFile> {
  const encrypted = spec.category === 'CONNECTION_DOC';
  const plain = await sha256Hex(spec.content as Uint8Array<ArrayBuffer>);
  const createdAt = new Date(Date.now() - spec.hoursAgo * 3600 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
  const meta: FileMetadata = {
    id: seedId(index),
    category: spec.category,
    originalName: spec.name,
    contentType: spec.contentType,
    size: spec.content.length,
    sha256: await storedSha256(plain, encrypted),
    sha256Plain: encrypted ? plain : null,
    storage: spec.storage,
    status: 'CONFIRMED',
    encrypted,
    createdAt,
    createdBy: spec.createdBy,
    ...(spec.zone ? { outage: { zone: spec.zone, stationId: spec.stationId ?? null, occurredAt: createdAt } } : {}),
  };
  const bytes = spec.content.slice();
  if (spec.corrupt) {
    // One byte changed in storage after writing ("bit rot"): the metadata keeps the original hash.
    bytes[bytes.length - 2] ^= 0xff;
  }
  return { meta, content: new Blob([bytes], { type: spec.contentType }) };
}

function seedId(index: number): string {
  const hex = (0x5eed0000 + index).toString(16).padStart(8, '0');
  return `${hex}-0000-4000-8000-${String(index).padStart(12, '0')}`;
}

function mulberry32(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
