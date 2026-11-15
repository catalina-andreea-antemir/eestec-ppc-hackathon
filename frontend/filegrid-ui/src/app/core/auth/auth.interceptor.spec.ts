import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { authInterceptor } from './auth.interceptor';

/** Unsigned JWT with the given payload (the backend verifies the signature, the UI only reads it). */
function fakeJwt(payload: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part(payload)}.x`;
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  // The contract claims: `sub` and `role` (a single string).
  const oldToken = fakeJwt({ sub: 'teren1', role: 'FIELD' });
  const newToken = fakeJwt({ sub: 'teren1', role: 'FIELD', v: 2 });

  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem('filegrid.accessToken', oldToken);
    sessionStorage.setItem('filegrid.refreshToken', 'refresh-1');

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adaugă header-ul Authorization la cererile către API', () => {
    http.get('/api/v1/files').subscribe();
    const req = backend.expectOne('/api/v1/files');
    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${oldToken}`);
    req.flush([]);
  });

  it('nu adaugă token la /auth/login', () => {
    http.post('/api/v1/auth/login', {}).subscribe();
    const req = backend.expectOne('/api/v1/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('la 401 reînnoiește token-ul o singură dată și repetă cererea', () => {
    let result: unknown;
    http.get('/api/v1/files').subscribe((body) => (result = body));

    backend.expectOne('/api/v1/files').flush(null, { status: 401, statusText: 'Unauthorized' });

    const refresh = backend.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh.flush({ accessToken: newToken, expiresIn: 900 });

    const retry = backend.expectOne('/api/v1/files');
    expect(retry.request.headers.get('Authorization')).toBe(`Bearer ${newToken}`);
    retry.flush(['ok']);

    expect(result).toEqual(['ok']);
    expect(sessionStorage.getItem('filegrid.accessToken')).toBe(newToken);
  });

  it('două cereri cu 401 în același timp folosesc un singur refresh', () => {
    http.get('/api/v1/a').subscribe();
    http.get('/api/v1/b').subscribe();
    backend.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });

    backend.expectOne('/api/v1/auth/refresh').flush({ accessToken: newToken, expiresIn: 900 });

    backend.expectOne('/api/v1/a').flush({});
    backend.expectOne('/api/v1/b').flush({});
  });

  it('dacă refresh-ul e respins, deloghează și trimite la /login', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    let failed = false;
    http.get('/api/v1/files').subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/v1/files').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(sessionStorage.getItem('filegrid.accessToken')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('fără refresh token nu mai cere refresh și deloghează', () => {
    sessionStorage.removeItem('filegrid.refreshToken');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http.get('/api/v1/files').subscribe({ error: () => undefined });

    backend.expectOne('/api/v1/files').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectNone('/api/v1/auth/refresh');

    expect(sessionStorage.getItem('filegrid.accessToken')).toBeNull();
  });

  it('la eroare de rețea pe refresh, utilizatorul rămâne logat', () => {
    http.get('/api/v1/files').subscribe({ error: () => undefined });

    backend.expectOne('/api/v1/files').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').error(new ProgressEvent('error'));

    expect(sessionStorage.getItem('filegrid.accessToken')).toBe(oldToken);
  });
});
