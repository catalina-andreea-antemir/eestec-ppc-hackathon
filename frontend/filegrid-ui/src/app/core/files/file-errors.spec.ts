import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { ApiError } from '../api/api-error';
import { describeFileError, verifyErrorOutcome } from './file-errors';

function apiError(status: number, code: string, extra: Partial<ApiError> = {}): HttpErrorResponse {
  const body: ApiError = { status, code, message: code, traceId: 'trace-1', ...extra };
  return new HttpErrorResponse({ status, error: body, headers: new HttpHeaders() });
}

describe('verifyErrorOutcome', () => {
  it('409 FILE_CORRUPT → „Corupt”, cu hash-ul așteptat și cel găsit', () => {
    const outcome = verifyErrorOutcome(
      apiError(409, 'FILE_CORRUPT', {
        message: 'fișier corupt',
        details: { fileId: 'f-1', expectedSha256: 'aaa', actualSha256: 'bbb' },
      }),
    );
    expect(outcome).toEqual({
      kind: 'corrupt',
      expectedSha256: 'aaa',
      actualSha256: 'bbb',
      message: 'fișier corupt',
      traceId: 'trace-1',
    });
  });

  it('alt 409 nu e „Corupt”', () => {
    expect(verifyErrorOutcome(apiError(409, 'UPLOAD_BUSY')).kind).toBe('error');
  });

  it('403 → mesaj de rol, nu „Corupt”', () => {
    const outcome = verifyErrorOutcome(apiError(403, 'FORBIDDEN'));
    expect(outcome.kind).toBe('error');
    expect(outcome.kind === 'error' && outcome.message).toContain('Rolul tău nu are acces');
  });
});

describe('describeFileError', () => {
  it('401 (sesiune sau link expirat) cere un link nou', () => {
    expect(describeFileError(apiError(401, 'UNAUTHORIZED'))).toContain('link nou');
  });

  it('rețea căzută (status 0)', () => {
    expect(describeFileError(new HttpErrorResponse({ status: 0 }))).toContain('Serverul nu răspunde');
  });

  it('503 cu Retry-After', () => {
    expect(describeFileError(apiError(503, 'STORAGE_UNAVAILABLE', { retryAfterSeconds: 2 }))).toContain('peste 2 s');
  });

  it('405 de la Spring (endpoint încă neimplementat, de exemplu GET /files)', () => {
    const springError = new HttpErrorResponse({
      status: 405,
      error: { status: 405, error: 'Method Not Allowed', path: '/api/v1/files' },
    });
    expect(describeFileError(springError)).toBe('Funcția aceasta nu este încă disponibilă pe server.');
  });

  it('un cod necunoscut fără mesaj arată codul HTTP', () => {
    expect(describeFileError(new HttpErrorResponse({ status: 418 }))).toBe('Eroare neașteptată (418).');
  });
});
