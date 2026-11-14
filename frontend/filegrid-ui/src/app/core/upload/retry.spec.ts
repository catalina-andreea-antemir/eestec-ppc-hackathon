import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { backoffMs, isTransientError, isTransientFileError, retryAfterMs, withRetry } from './retry';

const err = (status: number, code = 'X', extra: object = {}, headers: Record<string, string> = {}) =>
  new HttpErrorResponse({
    status,
    error: { status, code, message: '', traceId: '', ...extra },
    headers: new HttpHeaders(headers),
  });

describe('retry', () => {
  it('backoff: 1 s, 2 s, 4 s, 8 s, apoi plafon 10 s', () => {
    const opts = { baseDelayMs: 1000, maxDelayMs: 10_000, random: () => 0.5 };
    expect([1, 2, 3, 4, 5, 6].map((a) => backoffMs(a, opts))).toEqual([1000, 2000, 4000, 8000, 10_000, 10_000]);
  });

  it('variația e de ±20%', () => {
    const base = { baseDelayMs: 1000, maxDelayMs: 10_000 };
    expect(backoffMs(1, { ...base, random: () => 0 })).toBe(800);
    expect(backoffMs(1, { ...base, random: () => 1 })).toBe(1200);
  });

  it('Retry-After: din corp (retryAfterSeconds) sau din header', () => {
    expect(retryAfterMs(err(429, 'CATEGORY_SATURATED', { retryAfterSeconds: 3 }))).toBe(3000);
    expect(retryAfterMs(err(503, 'STORAGE_UNAVAILABLE', {}, { 'Retry-After': '5' }))).toBe(5000);
    expect(retryAfterMs(err(503, 'STORAGE_UNAVAILABLE'))).toBeNull();
  });

  it('erori trecătoare pentru bucăți: rețea, 429, 5xx, 409 UPLOAD_BUSY', () => {
    expect(isTransientError(new HttpErrorResponse({ status: 0 }))).toBe(true);
    expect(isTransientError(err(429))).toBe(true);
    expect(isTransientError(err(503))).toBe(true);
    expect(isTransientError(err(500))).toBe(true);
    expect(isTransientError(err(409, 'UPLOAD_BUSY'))).toBe(true);
    expect(isTransientError(err(409, 'CHUNK_OUT_OF_ORDER'))).toBe(false);
    expect(isTransientError(err(400))).toBe(false);
    expect(isTransientError(err(403))).toBe(false);
    expect(isTransientError(err(413))).toBe(false);
    expect(isTransientError(new Error('bug'))).toBe(false);
  });

  it('POST /files nu se reîncearcă la 500 (poate fi deja scris)', () => {
    expect(isTransientFileError(err(503))).toBe(true);
    expect(isTransientFileError(err(500))).toBe(false);
  });

  it('withRetry întoarce rezultatul după reîncercări și anunță fiecare reîncercare', async () => {
    let calls = 0;
    const retries: number[] = [];
    const result = await withRetry(
      async () => {
        if (++calls < 3) {
          throw err(503);
        }
        return 'ok';
      },
      { sleep: async () => undefined, random: () => 0.5, onRetry: (i) => retries.push(i.delayMs) },
    );
    expect(result).toBe('ok');
    expect(retries).toEqual([1000, 2000]);
  });
});
