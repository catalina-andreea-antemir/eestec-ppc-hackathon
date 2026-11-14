import {
  HttpErrorResponse,
  HttpEvent,
  HttpEventType,
  HttpHeaders,
  HttpProgressEvent,
  HttpRequest,
  HttpResponse,
  HttpSentEvent,
} from '@angular/common/http';
import { concat, defer, finalize, from, interval, map, Observable, of, switchMap, take, throwError, timer } from 'rxjs';

import { ApiError } from '../api/api-error';
import { Role } from '../auth/auth.models';
import {
  CATEGORY_RULES,
  ChunkAck,
  FileMetadata,
  ManualCategory,
  StorageType,
  UploadInit,
  UploadSession,
  ZONES,
  Zone,
} from '../upload/upload.models';
import { registerMockFile } from './mock-files';
import { getMockSettings } from './mock-settings';
import { Sha256, sha256Hex, Sha256State } from './sha256';

/**
 * Mock pentru POST /files și fluxul /uploads, după contract (docs/api/openapi.yaml).
 *
 * - Sesiunile de upload se păstrează în sessionStorage, ca reluarea după refresh să meargă și pe mock.
 * - Hash-ul final al video-ului e SHA-256 real al fișierului (calculat incremental, bucată cu bucată).
 * - Rețeaua e simulată: progresul vine cu viteza din setări, iar când browserul e offline
 *   (DevTools → Network → Offline) cererile eșuează ca o rețea căzută (status 0).
 * - Erori ocazionale (503, 429, 409 UPLOAD_BUSY, rețea, răspuns pierdut), cu probabilitatea din setări.
 */
export interface MockUser {
  sub: string;
  role: Role;
}

const SESSIONS_KEY = 'filegrid.mock.uploads';
const CHUNK_SIZE = 16 * 1024 * 1024;
const TICK_MS = 100;
const LATENCY_MS = 150;

const STORAGE: Record<ManualCategory, StorageType> = {
  OUTAGE_RECORD: 'S3',
  CONNECTION_DOC: 'DB',
  INSPECTION_VIDEO: 'FTP',
};

interface MockSession {
  session: UploadSession;
  createdBy: string;
  hash: Sha256State;
  chunkHashes: string[];
  result?: FileMetadata;
}

/** Sesiuni cu o cerere în curs (lease); doar în memorie, ca un refresh să nu lase lease-ul blocat. */
const busy = new Set<string>();

/**
 * Bucățile primite (Blob-uri din File.slice: referințe, nu copii), ca video-ul să se poată descărca
 * și verifica din pagina Căutare. Doar în memorie: după un refresh conținutul se pierde.
 */
const chunkBlobs = new Map<string, Blob[]>();

export function handleUploadRoutes(
  req: HttpRequest<unknown>,
  path: string,
  user: MockUser,
): Observable<HttpEvent<unknown>> | null {
  if (req.method === 'POST' && path === '/files') {
    return uploadFile(req as HttpRequest<FormData>, user);
  }
  if (req.method === 'POST' && path === '/uploads') {
    return initUpload(req as HttpRequest<UploadInit>, user);
  }
  const match = /^\/uploads\/([^/]+)(?:\/(chunks)\/(\d+)|\/(complete|abort))?$/.exec(path);
  if (!match) {
    return null;
  }
  const id = decodeURIComponent(match[1]);
  if (req.method === 'GET' && !match[2] && !match[4]) {
    return withSession(id, user, (s) => respond(200, s.session));
  }
  if (req.method === 'PUT' && match[2]) {
    return withSession(id, user, (s) => putChunk(req as HttpRequest<Blob>, s, Number(match[3])));
  }
  if (req.method === 'POST' && match[4] === 'complete') {
    return withSession(id, user, (s) => complete(s, user));
  }
  if (req.method === 'POST' && match[4] === 'abort') {
    return withSession(id, user, (s) => abort(s));
  }
  return null;
}

// ---------- POST /files ----------

function uploadFile(req: HttpRequest<FormData>, user: MockUser): Observable<HttpEvent<unknown>> {
  const form = req.body;
  const category = form?.get('category');
  const file = form?.get('file');
  if (category !== 'OUTAGE_RECORD' && category !== 'CONNECTION_DOC') {
    return apiError(400, 'VALIDATION_ERROR', 'POST /files acceptă doar OUTAGE_RECORD și CONNECTION_DOC.');
  }
  if (!canUpload(user.role, category)) {
    return apiError(403, 'FORBIDDEN', 'Rolul nu poate încărca această categorie.');
  }
  if (!(file instanceof File) || file.size === 0) {
    return apiError(400, 'VALIDATION_ERROR', 'Fișierul lipsește sau este gol.');
  }
  if (file.size > CATEGORY_RULES[category].maxBytes) {
    return apiError(413, 'PAYLOAD_TOO_LARGE', 'Fișierul depășește mărimea maximă din politica categoriei.');
  }

  let outage: FileMetadata['outage'];
  if (category === 'OUTAGE_RECORD') {
    const zone = form!.get('zone');
    const occurredAt = String(form!.get('occurredAt') ?? new Date().toISOString());
    const stationId = form!.get('stationId');
    if (!ZONES.includes(zone as Zone)) {
      return apiError(400, 'VALIDATION_ERROR', 'Zona trebuie să fie Muntenia, Banat sau Dobrogea.');
    }
    if (Number.isNaN(Date.parse(occurredAt))) {
      return apiError(400, 'VALIDATION_ERROR', 'occurredAt nu este un moment ISO-8601 valid.');
    }
    outage = { zone: zone as Zone, occurredAt, stationId: stationId ? String(stationId) : null };
  }

  const fault = pickFault(['503', '429', 'network']);
  if (fault) {
    return faultResponse(fault);
  }

  return concat(
    simulateTransfer(file.size),
    defer(() =>
      from(
        (async () => {
          const plain = await sha256Hex(await file.arrayBuffer());
          const encrypted = category === 'CONNECTION_DOC';
          const metadata: FileMetadata = {
            id: crypto.randomUUID(),
            category,
            originalName: file.name,
            contentType: file.type || 'application/octet-stream',
            size: file.size,
            // La documente, mock-ul nu cifrează de fapt: sha256 imită hash-ul bytes-ilor cifrați.
            sha256: encrypted ? await sha256Hex(new TextEncoder().encode(`aes-gcm:${plain}`)) : plain,
            sha256Plain: encrypted ? plain : null,
            storage: STORAGE[category],
            status: 'CONFIRMED',
            encrypted,
            createdAt: nowIso(),
            createdBy: user.sub,
            ...(outage ? { outage } : {}),
          };
          // Ca fișierul să apară în căutare (F5); conținutul rămâne doar o referință la File, nu o copie.
          registerMockFile(metadata, file);
          return metadata;
        })(),
      ).pipe(map((body) => response(201, body))),
    ),
  );
}

// ---------- /uploads ----------

function initUpload(req: HttpRequest<UploadInit>, user: MockUser): Observable<HttpEvent<unknown>> {
  const body = req.body;
  if (body?.category !== 'INSPECTION_VIDEO') {
    return apiError(400, 'VALIDATION_ERROR', 'POST /uploads acceptă doar INSPECTION_VIDEO.');
  }
  if (!canUpload(user.role, 'INSPECTION_VIDEO')) {
    return apiError(403, 'FORBIDDEN', 'Rolul nu poate încărca video-uri.');
  }
  if (!body.fileName || !Number.isInteger(body.totalSize) || body.totalSize < 1) {
    return apiError(400, 'VALIDATION_ERROR', 'fileName și totalSize (minim 1) sunt obligatorii.');
  }
  if (body.totalSize > CATEGORY_RULES.INSPECTION_VIDEO.maxBytes) {
    return apiError(413, 'PAYLOAD_TOO_LARGE', 'totalSize depășește maximul din politică (5 GB).');
  }
  const now = nowIso();
  const session: UploadSession = {
    uploadId: crypto.randomUUID(),
    category: 'INSPECTION_VIDEO',
    fileName: body.fileName,
    storage: STORAGE.INSPECTION_VIDEO,
    totalSize: body.totalSize,
    chunkSize: CHUNK_SIZE,
    totalChunks: Math.ceil(body.totalSize / CHUNK_SIZE),
    nextChunk: 1,
    receivedBytes: 0,
    status: 'IN_PROGRESS',
    createdAt: now,
    lastActivityAt: now,
  };
  save({ session, createdBy: user.sub, hash: new Sha256().state(), chunkHashes: [] });
  return respond(201, session);
}

function putChunk(req: HttpRequest<Blob>, stored: MockSession, n: number): Observable<HttpEvent<unknown>> {
  const s = stored.session;
  if (s.status !== 'IN_PROGRESS') {
    return apiError(409, 'UPLOAD_NOT_ACTIVE', 'Sesiunea de upload nu mai este activă.');
  }
  const body = req.body;
  if (!(body instanceof Blob) || !Number.isInteger(n) || n < 1 || n > s.totalChunks) {
    return apiError(400, 'VALIDATION_ERROR', `Bucata trebuie să fie între 1 și ${s.totalChunks}, cu corp binar.`);
  }
  // Bucată deja primită: confirmare idempotentă.
  if (n < s.nextChunk) {
    const ack: ChunkAck = { n, size: chunkLength(s, n), sha256: stored.chunkHashes[n - 1], nextChunk: s.nextChunk, duplicate: true };
    return respond(200, ack);
  }
  if (n > s.nextChunk) {
    return apiError(409, 'CHUNK_OUT_OF_ORDER', `Se aștepta bucata ${s.nextChunk}, s-a primit ${n}.`, {
      details: { expectedChunk: s.nextChunk, receivedChunk: n },
    });
  }
  if (body.size !== chunkLength(s, n)) {
    return apiError(400, 'VALIDATION_ERROR', `Bucata ${n} trebuie să aibă ${chunkLength(s, n)} octeți.`);
  }
  if (busy.has(s.uploadId)) {
    return apiError(409, 'UPLOAD_BUSY', 'Altă cerere scrie în acest moment în aceeași sesiune; reîncearcă.', {
      retryAfterSeconds: 1,
    });
  }
  const fault = pickFault(['503', '429', 'busy', 'network', 'lost-response']);
  if (fault && fault !== 'lost-response') {
    return faultResponse(fault);
  }

  busy.add(s.uploadId);
  return concat(
    simulateTransfer(body.size),
    defer(() =>
      from(
        (async () => {
          const bytes = new Uint8Array(await body.arrayBuffer());
          // Starea se recitește: între timp sesiunea putea fi anulată.
          const current = load(s.uploadId);
          if (!current || current.session.status !== 'IN_PROGRESS' || current.session.nextChunk !== n) {
            throw httpError(409, 'UPLOAD_NOT_ACTIVE', 'Sesiunea s-a schimbat în timpul scrierii.');
          }
          const chunkHash = await sha256Hex(bytes);
          current.hash = new Sha256(current.hash).update(bytes).state();
          current.chunkHashes[n - 1] = chunkHash;
          const blobs = chunkBlobs.get(s.uploadId) ?? [];
          blobs[n - 1] = body;
          chunkBlobs.set(s.uploadId, blobs);
          current.session.nextChunk = n + 1;
          current.session.receivedBytes += bytes.length;
          current.session.lastActivityAt = nowIso();
          save(current);
          if (fault === 'lost-response') {
            // Serverul a scris bucata, dar răspunsul s-a pierdut: retrimiterea primește duplicate: true.
            throw networkError();
          }
          const ack: ChunkAck = { n, size: bytes.length, sha256: chunkHash, nextChunk: n + 1, duplicate: false };
          return ack;
        })(),
      ).pipe(map((ack) => response(200, ack))),
    ),
  ).pipe(finalize(() => busy.delete(s.uploadId)));
}

function complete(stored: MockSession, user: MockUser): Observable<HttpEvent<unknown>> {
  const s = stored.session;
  if (s.status === 'COMPLETED' && stored.result) {
    return respond(200, stored.result);
  }
  if (s.status !== 'IN_PROGRESS') {
    return apiError(409, 'UPLOAD_NOT_ACTIVE', 'Sesiunea a fost anulată sau abandonată.');
  }
  if (s.nextChunk <= s.totalChunks) {
    return apiError(409, 'UPLOAD_INCOMPLETE', `Lipsesc bucăți; următoarea așteptată este ${s.nextChunk} din ${s.totalChunks}.`, {
      details: { nextChunk: s.nextChunk, totalChunks: s.totalChunks },
    });
  }
  const fault = pickFault(['503']);
  if (fault) {
    return faultResponse(fault);
  }
  stored.result = {
    id: crypto.randomUUID(),
    category: 'INSPECTION_VIDEO',
    originalName: s.fileName,
    contentType: 'video/mp4',
    size: s.totalSize,
    sha256: new Sha256(stored.hash).hex(),
    sha256Plain: null,
    storage: s.storage,
    status: 'CONFIRMED',
    encrypted: false,
    createdAt: nowIso(),
    createdBy: user.sub,
  };
  s.status = 'COMPLETED';
  s.lastActivityAt = nowIso();
  save(stored);
  // Conținutul există doar dacă toate bucățile au fost primite în această încărcare a paginii.
  const blobs = chunkBlobs.get(s.uploadId) ?? [];
  const complete = blobs.length === s.totalChunks && blobs.every(Boolean);
  registerMockFile(stored.result, complete ? new Blob(blobs, { type: 'video/mp4' }) : null);
  chunkBlobs.delete(s.uploadId);
  return respond(201, stored.result);
}

function abort(stored: MockSession): Observable<HttpEvent<unknown>> {
  const s = stored.session;
  if (s.status === 'COMPLETED') {
    return apiError(409, 'UPLOAD_NOT_ACTIVE', 'Upload-ul a fost deja finalizat.');
  }
  s.status = 'ABORTED';
  s.lastActivityAt = nowIso();
  save(stored);
  chunkBlobs.delete(s.uploadId);
  return respond(200, s);
}

function withSession(
  id: string,
  user: MockUser,
  handler: (session: MockSession) => Observable<HttpEvent<unknown>>,
): Observable<HttpEvent<unknown>> {
  if (!navigator.onLine) {
    return offline();
  }
  const stored = load(id);
  if (!stored) {
    return apiError(404, 'NOT_FOUND', 'Upload-ul nu există.');
  }
  if (stored.createdBy !== user.sub && user.role !== 'ADMIN') {
    return apiError(403, 'FORBIDDEN', 'Doar cine a inițiat upload-ul (sau ADMIN) îl poate accesa.');
  }
  return handler(stored);
}

// ---------- rețea simulată și erori ----------

/** Progresul trimiterii corpului, cu viteza din setări; eșuează dacă browserul trece offline. */
function simulateTransfer(totalBytes: number): Observable<HttpEvent<never>> {
  if (!navigator.onLine) {
    return offline();
  }
  const bytesPerTick = Math.max(1, (getMockSettings().speedMBps * 1024 * 1024 * TICK_MS) / 1000);
  const ticks = Math.max(1, Math.ceil(totalBytes / bytesPerTick));
  const sent: HttpSentEvent = { type: HttpEventType.Sent };
  return concat(
    of(sent),
    interval(TICK_MS).pipe(
      take(ticks),
      map((i): HttpProgressEvent => {
        if (!navigator.onLine) {
          throw networkError();
        }
        return { type: HttpEventType.UploadProgress, loaded: Math.min(totalBytes, (i + 1) * bytesPerTick), total: totalBytes };
      }),
    ),
  );
}

type Fault = '503' | '429' | 'busy' | 'network' | 'lost-response';

function pickFault<F extends Fault>(options: F[]): F | null {
  if (Math.random() >= getMockSettings().failureRate) {
    return null;
  }
  return options[Math.floor(Math.random() * options.length)];
}

function faultResponse(fault: Exclude<Fault, 'lost-response'>): Observable<never> {
  switch (fault) {
    case '503':
      return apiError(503, 'STORAGE_UNAVAILABLE', 'Storage-ul este indisponibil momentan.', { retryAfterSeconds: 2 });
    case '429':
      return apiError(429, 'CATEGORY_SATURATED', 'Prea multe cereri paralele pentru această categorie.', {
        retryAfterSeconds: 1,
      });
    case 'busy':
      return apiError(409, 'UPLOAD_BUSY', 'Altă cerere scrie în acest moment în aceeași sesiune; reîncearcă.', {
        retryAfterSeconds: 1,
      });
    case 'network':
      return offline();
  }
}

function canUpload(role: Role, category: ManualCategory): boolean {
  return role === 'ADMIN' || CATEGORY_RULES[category].roles.includes(role);
}

function chunkLength(s: UploadSession, n: number): number {
  return n < s.totalChunks ? s.chunkSize : s.totalSize - (s.totalChunks - 1) * s.chunkSize;
}

function traceId(): string {
  return crypto.randomUUID().replace(/-/g, '');
}

export function nowIso(): string {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function response<T>(status: number, body: T): HttpResponse<T> {
  return new HttpResponse({ status, body, headers: new HttpHeaders({ 'X-Trace-Id': traceId() }) });
}

export function respond<T>(status: number, body: T): Observable<HttpEvent<T>> {
  if (!navigator.onLine) {
    return offline();
  }
  return timer(LATENCY_MS).pipe(map(() => response(status, structuredClone(body))));
}

function httpError(
  status: number,
  code: string,
  message: string,
  extra: Pick<ApiError, 'retryAfterSeconds' | 'details'> = {},
): HttpErrorResponse {
  const id = traceId();
  const body: ApiError = { status, code, message, traceId: id, ...extra };
  let headers = new HttpHeaders({ 'X-Trace-Id': id });
  if (extra.retryAfterSeconds !== undefined) {
    headers = headers.set('Retry-After', String(extra.retryAfterSeconds));
  }
  return new HttpErrorResponse({ status, statusText: message, error: body, headers });
}

export function apiError(
  status: number,
  code: string,
  message: string,
  extra: Pick<ApiError, 'retryAfterSeconds' | 'details'> = {},
): Observable<never> {
  return timer(LATENCY_MS).pipe(switchMap(() => throwError(() => httpError(status, code, message, extra))));
}

function networkError(): HttpErrorResponse {
  return new HttpErrorResponse({ status: 0, statusText: 'Unknown Error', error: new ProgressEvent('error') });
}

function offline(): Observable<never> {
  return timer(LATENCY_MS).pipe(switchMap(() => throwError(networkError)));
}

// ---------- stare păstrată în sessionStorage ----------

function loadAll(): Record<string, MockSession> {
  try {
    return JSON.parse(sessionStorage.getItem(SESSIONS_KEY) ?? '{}') as Record<string, MockSession>;
  } catch {
    return {};
  }
}

function load(id: string): MockSession | null {
  return loadAll()[id] ?? null;
}

function save(stored: MockSession): void {
  const all = loadAll();
  all[stored.session.uploadId] = stored;
  try {
    sessionStorage.setItem(SESSIONS_KEY, JSON.stringify(all));
  } catch {
    /* ignorat */
  }
}
