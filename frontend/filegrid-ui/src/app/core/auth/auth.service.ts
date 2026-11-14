import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize, map, Observable, shareReplay, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthUser, LoginRequest, RefreshRequest, Role, TokenResponse } from './auth.models';

const ACCESS_KEY = 'filegrid.accessToken';
const REFRESH_KEY = 'filegrid.refreshToken';

/**
 * Ține token-urile și starea de autentificare.
 *
 * Token-urile stau în sessionStorage: supraviețuiesc unui refresh de pagină
 * (ca upload-ul în bucăți să se poată relua), dar dispar la închiderea tab-ului.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly accessToken = signal<string | null>(readStorage(ACCESS_KEY));
  private refreshInFlight$: Observable<string> | null = null;

  /** Utilizatorul curent, extras din payload-ul JWT (null dacă nu e autentificat). */
  readonly user = computed<AuthUser | null>(() => decodeUser(this.accessToken()));
  readonly isLoggedIn = computed(() => this.user() !== null);

  /** Token-ul pe care interceptorul îl pune în header-ul Authorization. */
  getAccessToken(): string | null {
    return this.accessToken();
  }

  hasRole(role: Role): boolean {
    const roles = this.user()?.roles ?? [];
    return roles.includes(role) || roles.includes('ADMIN');
  }

  login(request: LoginRequest): Observable<AuthUser> {
    return this.http.post<TokenResponse>(`${environment.apiBaseUrl}/auth/login`, request).pipe(
      tap((tokens) => this.storeTokens(tokens)),
      map(() => this.user()!),
    );
  }

  /**
   * Cere un accessToken nou cu refreshToken-ul (POST /auth/refresh).
   * Dacă mai multe cereri primesc 401 în același timp, toate așteaptă aceeași
   * reînnoire, în loc să trimită fiecare câte un refresh.
   */
  refresh(): Observable<string> {
    if (this.refreshInFlight$) {
      return this.refreshInFlight$;
    }
    const refreshToken = readStorage(REFRESH_KEY);
    const body: RefreshRequest = { refreshToken: refreshToken ?? '' };

    this.refreshInFlight$ = this.http
      .post<TokenResponse>(`${environment.apiBaseUrl}/auth/refresh`, body)
      .pipe(
        tap((tokens) => this.storeTokens(tokens)),
        map((tokens) => tokens.accessToken),
        finalize(() => (this.refreshInFlight$ = null)),
        shareReplay(1),
      );
    return this.refreshInFlight$;
  }

  logout(redirect = true): void {
    removeStorage(ACCESS_KEY);
    removeStorage(REFRESH_KEY);
    this.accessToken.set(null);
    if (redirect) {
      void this.router.navigate(['/login']);
    }
  }

  private storeTokens(tokens: TokenResponse): void {
    writeStorage(ACCESS_KEY, tokens.accessToken);
    if (tokens.refreshToken) {
      writeStorage(REFRESH_KEY, tokens.refreshToken);
    }
    this.accessToken.set(tokens.accessToken);
  }
}

/** Citește `sub` și `roles` din payload-ul JWT. Semnătura o verifică doar backend-ul. */
function decodeUser(token: string | null): AuthUser | null {
  if (!token) {
    return null;
  }
  try {
    const payload = JSON.parse(base64UrlDecode(token.split('.')[1]));
    const roles: Role[] = Array.isArray(payload.roles) ? payload.roles : [];
    return { username: String(payload.sub ?? ''), roles };
  } catch {
    return null;
  }
}

function base64UrlDecode(value: string): string {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

// sessionStorage poate arunca (mod privat, stocare blocată): interfața merge și fără el.
function readStorage(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    /* ignorat */
  }
}

function removeStorage(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* ignorat */
  }
}
