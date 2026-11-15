import { HttpErrorResponse, HttpEvent, HttpParams, HttpRequest, HttpResponse } from '@angular/common/http';
import { lastValueFrom, Observable } from 'rxjs';

import { ApiError } from '../api/api-error';
import { Role } from '../auth/auth.models';
import { FilePage, VerifyResult } from '../files/files.models';
import { handleFileRoutes } from './mock-files';

/** Runs a request through the mock and returns the response body or the contract error. */
async function call<T>(role: Role, method: string, path: string, params: Record<string, string> = {}): Promise<T> {
  const req = new HttpRequest(method as 'GET', `/api/v1${path}`, null, { params: new HttpParams({ fromObject: params }) });
  const events$ = handleFileRoutes(req, path, { sub: 'test', role }) as Observable<HttpEvent<T>>;
  const last = await lastValueFrom(events$);
  return (last as HttpResponse<T>).body as T;
}

async function callError(role: Role, method: string, path: string, params: Record<string, string> = {}): Promise<ApiError> {
  try {
    await call(role, method, path, params);
  } catch (error) {
    return (error as HttpErrorResponse).error as ApiError;
  }
  throw new Error('se aștepta o eroare');
}

describe('mock GET /files', () => {
  beforeEach(() => sessionStorage.clear());

  it('fără categorie, restrânge la categoriile rolului', async () => {
    const page = await call<FilePage>('FIELD', 'GET', '/files', { size: '100' });
    expect(page.items.length).toBeGreaterThan(0);
    expect(new Set(page.items.map((f) => f.category))).toEqual(new Set(['OUTAGE_RECORD', 'INSPECTION_VIDEO']));
  });

  it('o categorie nepermisă rolului dă 403', async () => {
    const error = await callError('DISPATCH', 'GET', '/files', { category: 'CONNECTION_DOC' });
    expect(error.status).toBe(403);
    expect(error.code).toBe('FORBIDDEN');
  });

  it('paginează: page de la 1, size, totalItems, totalPages', async () => {
    const first = await call<FilePage>('ADMIN', 'GET', '/files', { page: '1', size: '20' });
    expect(first.items).toHaveLength(20);
    expect(first.totalPages).toBe(Math.ceil(first.totalItems / 20));
    const last = await call<FilePage>('ADMIN', 'GET', '/files', { page: String(first.totalPages), size: '20' });
    expect(last.items.length).toBe(first.totalItems - (first.totalPages - 1) * 20);
    expect(last.items.map((f) => f.id)).not.toContain(first.items[0].id);
  });

  it('size peste 100 dă 400', async () => {
    expect((await callError('ADMIN', 'GET', '/files', { size: '101' })).code).toBe('VALIDATION_ERROR');
  });

  it('q caută în nume, dar nu se aplică documentelor de racordare', async () => {
    const page = await call<FilePage>('ADMIN', 'GET', '/files', { q: 'brasov', size: '100' });
    const notDocs = page.items.filter((f) => f.category !== 'CONNECTION_DOC');
    expect(notDocs.length).toBeGreaterThan(0);
    expect(notDocs.every((f) => f.originalName.toLowerCase().includes('brasov'))).toBe(true);
    expect(page.items.some((f) => f.category === 'CONNECTION_DOC')).toBe(true);
  });

  it('from inclusiv, to exclusiv, după createdAt', async () => {
    const all = await call<FilePage>('ADMIN', 'GET', '/files', { size: '100' });
    const pivot = all.items[5].createdAt;
    const after = await call<FilePage>('ADMIN', 'GET', '/files', { from: pivot, size: '100' });
    const before = await call<FilePage>('ADMIN', 'GET', '/files', { to: pivot, size: '100' });
    expect(after.items.every((f) => f.createdAt >= pivot)).toBe(true);
    expect(before.items.every((f) => f.createdAt < pivot)).toBe(true);
    expect(after.totalItems + before.totalItems).toBe(all.totalItems);
  });
});

describe('mock GET /files/{id}/verify', () => {
  async function findByName(name: string) {
    const page = await call<FilePage>('ADMIN', 'GET', '/files', { q: name, size: '100' });
    return page.items.find((f) => f.originalName === name)!;
  }

  it('un fișier intact: 200 cu hash-ul identic', async () => {
    const file = (await call<FilePage>('FIELD', 'GET', '/files', { category: 'OUTAGE_RECORD' })).items.find(
      (f) => !f.originalName.includes('CORUPT'),
    )!;
    const result = await call<VerifyResult>('FIELD', 'GET', `/files/${file.id}/verify`);
    expect(result.ok).toBe(true);
    expect(result.actualSha256).toBe(file.sha256);
  });

  it('fișierul corupt intenționat: 409 FILE_CORRUPT cu hash-urile', async () => {
    const file = await findByName('avarie-ST-TIMISOARA-007-CORUPT.csv');
    const error = await callError('FIELD', 'GET', `/files/${file.id}/verify`);
    expect(error.status).toBe(409);
    expect(error.code).toBe('FILE_CORRUPT');
    expect(error.details?.['expectedSha256']).toBe(file.sha256);
    expect(error.details?.['actualSha256']).not.toBe(file.sha256);
  });

  it('deep=true la un document intact verifică și sha256Plain', async () => {
    const doc = (await call<FilePage>('CONNECTIONS', 'GET', '/files')).items.find((f) => !f.originalName.includes('CORUPT'))!;
    const result = await call<VerifyResult>('CONNECTIONS', 'GET', `/files/${doc.id}/verify`, { deep: 'true' });
    expect(result.deep).toBe(true);
    expect(result.actualSha256Plain).toBe(doc.sha256Plain);
  });

  it('rolul fără acces la categoria fișierului primește 403', async () => {
    const doc = (await call<FilePage>('CONNECTIONS', 'GET', '/files')).items[0];
    expect((await callError('FIELD', 'GET', `/files/${doc.id}/verify`)).status).toBe(403);
  });
});
