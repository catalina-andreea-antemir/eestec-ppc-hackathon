import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from './auth.service';

function fakeJwt(payload: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part(payload)}.x`;
}

describe('AuthService', () => {
  let auth: AuthService;
  let backend: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('la login salvează token-urile și citește claim-urile sub și role', () => {
    auth.login({ username: 'dispecer', password: 'x' }).subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      accessToken: fakeJwt({ sub: 'dispecer', role: 'DISPATCH' }),
      refreshToken: 'refresh-1',
      expiresIn: 900,
    });

    expect(auth.user()).toEqual({ username: 'dispecer', role: 'DISPATCH' });
    expect(sessionStorage.getItem('filegrid.refreshToken')).toBe('refresh-1');
  });

  it('hasRole respectă rolul și dă acces complet lui ADMIN', () => {
    auth.login({ username: 'racordari1', password: 'x' }).subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      accessToken: fakeJwt({ sub: 'racordari1', role: 'CONNECTIONS' }),
      refreshToken: 'r',
      expiresIn: 900,
    });
    expect(auth.hasRole('CONNECTIONS')).toBe(true);
    expect(auth.hasRole('FIELD', 'DISPATCH')).toBe(false);

    auth.logout(false);
    auth.login({ username: 'admin', password: 'x' }).subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      accessToken: fakeJwt({ sub: 'admin', role: 'ADMIN' }),
      refreshToken: 'r',
      expiresIn: 900,
    });
    expect(auth.hasRole('FIELD')).toBe(true);
  });

  it('un rol necunoscut în token nu dă niciun drept', () => {
    sessionStorage.setItem('filegrid.accessToken', fakeJwt({ sub: 'x', role: 'ROOT' }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fresh = TestBed.inject(AuthService);
    expect(fresh.user()).toEqual({ username: 'x', role: null });
    expect(fresh.hasRole('FIELD')).toBe(false);
    backend = TestBed.inject(HttpTestingController);
  });
});
