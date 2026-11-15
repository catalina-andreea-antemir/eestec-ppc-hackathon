import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { ApiError } from '../api/api-error';
import { FileMismatchError, runChunkedUpload, UploadNotActiveError } from './chunked-upload';
import { RetryOptions, RetryTimeoutError } from './retry';
import { ChunkTransport } from './upload-api';
import { ChunkAck, FileMetadata, UploadInit, UploadSession } from './upload.models';

// ---------- helpers ----------

function apiError(status: number, code: string, extra: Partial<ApiError> = {}, headers: Record<string, string> = {}) {
  const body: ApiError = { status, code, message: code, traceId: 't', ...extra };
  return new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers) });
}

const networkError = () => new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') });

/** Fake clock: `sleep` only advances time and records how long was waited. */
function fakeClock() {
  let now = 0;
  const sleeps: number[] = [];
  const retry: Partial<RetryOptions> = {
    now: () => now,
    sleep: async (ms) => {
      sleeps.push(ms);
      now += ms;
    },
    random: () => 0.5, // no jitter: backoff of exactly 1 s, 2 s, 4 s...
  };
  return { sleeps, retry };
}

type PutHook = (n: number, attempt: number) => unknown;

/** Fake server following the contract rules (sequential, idempotent, CHUNK_OUT_OF_ORDER, UPLOAD_INCOMPLETE). */
class FakeServer implements ChunkTransport {
  readonly chunkSize = 10;
  session: UploadSession | null = null;
  readonly sizes: number[] = [];
  readonly calls: string[] = [];
  private readonly attempts = new Map<number, number>();
  onPut: PutHook | null = null;
  onComplete: ((attempt: number) => unknown) | null = null;
  private completeAttempts = 0;

  async initUpload(init: UploadInit): Promise<UploadSession> {
    this.calls.push('init');
    this.session = {
      uploadId: 'u-1',
      category: init.category,
      fileName: init.fileName,
      storage: 'FTP',
      totalSize: init.totalSize,
      chunkSize: this.chunkSize,
      totalChunks: Math.ceil(init.totalSize / this.chunkSize),
      nextChunk: 1,
      receivedBytes: 0,
      status: 'IN_PROGRESS',
      createdAt: '2026-11-14T10:00:00Z',
      lastActivityAt: '2026-11-14T10:00:00Z',
    };
    return { ...this.session };
  }

  async getUpload(): Promise<UploadSession> {
    this.calls.push('get');
    if (!this.session) {
      throw apiError(404, 'NOT_FOUND');
    }
    return { ...this.session };
  }

  async putChunk(_id: string, n: number, body: Blob): Promise<ChunkAck> {
    this.calls.push(`put ${n}`);
    const attempt = (this.attempts.get(n) ?? 0) + 1;
    this.attempts.set(n, attempt);
    const s = this.session!;
    const hook = this.onPut?.(n, attempt);
    if (hook && hook !== 'lost-response') {
      throw hook;
    }
    if (n < s.nextChunk) {
      return { n, size: this.sizes[n - 1], sha256: `h${n}`, nextChunk: s.nextChunk, duplicate: true };
    }
    if (n > s.nextChunk) {
      throw apiError(409, 'CHUNK_OUT_OF_ORDER', { details: { expectedChunk: s.nextChunk, receivedChunk: n } });
    }
    this.sizes[n - 1] = body.size;
    s.nextChunk = n + 1;
    s.receivedBytes += body.size;
    if (hook === 'lost-response') {
      throw networkError(); // written on the server, but the response was lost
    }
    return { n, size: body.size, sha256: `h${n}`, nextChunk: s.nextChunk, duplicate: false };
  }

  async completeUpload(): Promise<FileMetadata> {
    this.calls.push('complete');
    const hook = this.onComplete?.(++this.completeAttempts);
    if (hook) {
      throw hook;
    }
    const s = this.session!;
    if (s.nextChunk <= s.totalChunks) {
      throw apiError(409, 'UPLOAD_INCOMPLETE', { details: { nextChunk: s.nextChunk, totalChunks: s.totalChunks } });
    }
    s.status = 'COMPLETED';
    return {
      id: 'f-1',
      category: 'INSPECTION_VIDEO',
      originalName: s.fileName,
      contentType: 'video/mp4',
      size: s.totalSize,
      sha256: 'a'.repeat(64),
      sha256Plain: null,
      storage: 'FTP',
      status: 'CONFIRMED',
      encrypted: false,
      createdAt: '2026-11-14T10:05:00Z',
      createdBy: 'teren1',
    };
  }

  async abortUpload(): Promise<UploadSession> {
    this.calls.push('abort');
    this.session!.status = 'ABORTED';
    return { ...this.session! };
  }

  puts(): string[] {
    return this.calls.filter((c) => c.startsWith('put'));
  }
}

/** 35 bytes -> 4 chunks of 10, 10, 10 and 5. */
const video = () => new File([new Uint8Array(35)], 'drona.mp4', { type: 'video/mp4' });

// ---------- tests ----------

describe('runChunkedUpload', () => {
  let server: FakeServer;
  let clock: ReturnType<typeof fakeClock>;

  beforeEach(() => {
    server = new FakeServer();
    clock = fakeClock();
  });

  it('trimite bucățile strict secvențial, cu mărimile corecte, apoi finalizează', async () => {
    const sessions: UploadSession[] = [];
    const result = await runChunkedUpload(server, video(), { retry: clock.retry, onSession: (s) => sessions.push(s) });

    expect(server.calls).toEqual(['init', 'put 1', 'put 2', 'put 3', 'put 4', 'complete']);
    expect(server.sizes).toEqual([10, 10, 10, 5]);
    expect(sessions[0].uploadId).toBe('u-1');
    expect(result.sha256).toHaveLength(64);
    expect(clock.sleeps).toEqual([]);
  });

  it('raportează progresul până la mărimea totală', async () => {
    const sent: number[] = [];
    await runChunkedUpload(server, video(), { retry: clock.retry, onProgress: (p) => sent.push(p.sentBytes) });
    expect(Math.max(...sent)).toBe(35);
    expect(sent).toEqual([...sent].sort((a, b) => a - b));
  });

  it('la rețea căzută reîncearcă aceeași bucată cu backoff exponențial (1 s, 2 s, 4 s)', async () => {
    server.onPut = (n, attempt) => (n === 2 && attempt <= 3 ? networkError() : null);
    await runChunkedUpload(server, video(), { retry: clock.retry });

    expect(server.puts()).toEqual(['put 1', 'put 2', 'put 2', 'put 2', 'put 2', 'put 3', 'put 4']);
    expect(clock.sleeps).toEqual([1000, 2000, 4000]);
    expect(server.sizes).toEqual([10, 10, 10, 5]);
  });

  it('respectă Retry-After la 503 și retryAfterSeconds la 409 UPLOAD_BUSY', async () => {
    server.onPut = (n, attempt) => {
      if (n === 1 && attempt === 1) {
        return apiError(503, 'STORAGE_UNAVAILABLE', {}, { 'Retry-After': '7' });
      }
      if (n === 3 && attempt === 1) {
        return apiError(409, 'UPLOAD_BUSY', { retryAfterSeconds: 2 });
      }
      if (n === 4 && attempt === 1) {
        return apiError(429, 'CATEGORY_SATURATED', { retryAfterSeconds: 1 });
      }
      return null;
    };
    await runChunkedUpload(server, video(), { retry: clock.retry });
    expect(clock.sleeps).toEqual([7000, 2000, 1000]);
    expect(server.session!.status).toBe('COMPLETED');
  });

  it('dacă răspunsul se pierde, retrimiterea primește duplicate: true și nu scrie de două ori', async () => {
    server.onPut = (n, attempt) => (n === 2 && attempt === 1 ? 'lost-response' : null);
    await runChunkedUpload(server, video(), { retry: clock.retry });

    expect(server.puts()).toEqual(['put 1', 'put 2', 'put 2', 'put 3', 'put 4']);
    expect(server.session!.receivedBytes).toBe(35);
  });

  it('la CHUNK_OUT_OF_ORDER continuă de la details.expectedChunk', async () => {
    // The server "loses" chunk 3 (e.g. storage rolled back to an earlier state).
    server.onPut = (n, attempt) => {
      if (n === 4 && attempt === 1) {
        server.session!.nextChunk = 3;
        server.session!.receivedBytes = 20;
      }
      return null;
    };
    await runChunkedUpload(server, video(), { retry: clock.retry });
    expect(server.puts()).toEqual(['put 1', 'put 2', 'put 3', 'put 4', 'put 3', 'put 4']);
    expect(server.session!.status).toBe('COMPLETED');
  });

  it('la CHUNK_OUT_OF_ORDER fără details întreabă serverul (GET /uploads/{id})', async () => {
    server.onPut = (n, attempt) => {
      if (n === 3 && attempt === 1) {
        server.session!.nextChunk = 2;
        return apiError(409, 'CHUNK_OUT_OF_ORDER');
      }
      return null;
    };
    await runChunkedUpload(server, video(), { retry: clock.retry });
    expect(server.calls).toEqual(['init', 'put 1', 'put 2', 'put 3', 'get', 'put 2', 'put 3', 'put 4', 'complete']);
  });

  it('reia o sesiune existentă de la nextChunk (după refresh de pagină)', async () => {
    await server.initUpload({ category: 'INSPECTION_VIDEO', fileName: 'drona.mp4', totalSize: 35 });
    server.session!.nextChunk = 3;
    server.session!.receivedBytes = 20;
    server.sizes.push(10, 10);
    server.calls.length = 0;

    await runChunkedUpload(server, video(), { uploadId: 'u-1', retry: clock.retry });
    expect(server.calls).toEqual(['get', 'put 3', 'put 4', 'complete']);
  });

  it('la reluare refuză un fișier cu alt nume sau altă mărime, fără să trimită bucăți', async () => {
    await server.initUpload({ category: 'INSPECTION_VIDEO', fileName: 'drona.mp4', totalSize: 35 });
    server.calls.length = 0;
    const other = new File([new Uint8Array(36)], 'drona.mp4');

    await expect(runChunkedUpload(server, other, { uploadId: 'u-1', retry: clock.retry })).rejects.toBeInstanceOf(
      FileMismatchError,
    );
    expect(server.calls).toEqual(['get']);
  });

  it('la reluarea unei sesiuni anulate cere un upload nou', async () => {
    await server.initUpload({ category: 'INSPECTION_VIDEO', fileName: 'drona.mp4', totalSize: 35 });
    server.session!.status = 'ABORTED';
    await expect(runChunkedUpload(server, video(), { uploadId: 'u-1', retry: clock.retry })).rejects.toBeInstanceOf(
      UploadNotActiveError,
    );
  });

  it('la reluarea unei sesiuni deja finalizate doar repetă complete (idempotent)', async () => {
    await server.initUpload({ category: 'INSPECTION_VIDEO', fileName: 'drona.mp4', totalSize: 35 });
    server.session!.nextChunk = 5;
    server.session!.status = 'COMPLETED';
    server.calls.length = 0;
    const result = await runChunkedUpload(server, video(), { uploadId: 'u-1', retry: clock.retry });
    expect(server.calls).toEqual(['get', 'complete']);
    expect(result.id).toBe('f-1');
  });

  it('la UPLOAD_INCOMPLETE trimite bucățile lipsă și finalizează din nou', async () => {
    server.onComplete = (attempt) => {
      if (attempt === 1) {
        server.session!.nextChunk = 4;
      }
      return null;
    };
    await runChunkedUpload(server, video(), { retry: clock.retry });
    expect(server.calls.slice(-3)).toEqual(['complete', 'put 4', 'complete']);
  });

  it('renunță după 2 minute de reîncercări (RetryTimeoutError)', async () => {
    server.onPut = (n) => (n === 2 ? apiError(503, 'STORAGE_UNAVAILABLE') : null);
    const error = await runChunkedUpload(server, video(), { retry: clock.retry }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(RetryTimeoutError);
    const waited = clock.sleeps.reduce((a, b) => a + b, 0);
    expect(waited).toBeLessThanOrEqual(120_000);
    expect(waited).toBeGreaterThan(100_000);
    // The session stays on the server, so it can be resumed.
    expect(server.session!.status).toBe('IN_PROGRESS');
  });

  it('nu reîncearcă erorile definitive (403)', async () => {
    server.onPut = () => apiError(403, 'FORBIDDEN');
    await expect(runChunkedUpload(server, video(), { retry: clock.retry })).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(server.puts()).toEqual(['put 1']);
    expect(clock.sleeps).toEqual([]);
  });

  it('se oprește la abort (pauză), fără alte cereri', async () => {
    const controller = new AbortController();
    server.onPut = (n) => {
      if (n === 2) {
        controller.abort();
      }
      return null;
    };
    const error = await runChunkedUpload(server, video(), { retry: clock.retry, signal: controller.signal }).catch(
      (e: unknown) => e,
    );
    expect((error as DOMException).name).toBe('AbortError');
    expect(server.puts()).toEqual(['put 1', 'put 2']);
  });
});
