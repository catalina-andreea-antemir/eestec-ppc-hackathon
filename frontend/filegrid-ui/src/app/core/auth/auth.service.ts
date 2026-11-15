import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize, map, Observable, shareReplay, tap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AccessToken, AuthUser, LoginRequest, RefreshRequest, Role, TokenPair } from './auth.models';

const ROLES: readonly Role[] = ['DISPATCH', 'FIELD', 'CONNECTIONS', 'METER_SERVICE', 'ADMIN'];

const ACCESS_KEY = 'filegrid.accessToken';
const REFRESH_KEY = 'filegrid.refreshToken';

/**
 * Holds the tokens and the authentication state.
 *
 * Tokens live in sessionStorage: they survive a page refresh (so a chunked upload can resume)
 * but disappear when the tab is closed.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly accessToken = signal<string | null>(readStorage(ACCESS_KEY));
  private refreshInFlight$: Observable<string> | null = null;

  /** The current user, decoded from the JWT payload (null when not authenticated). */
  readonly user = computed<AuthUser | null>(() => decodeUser(this.accessToken()));
  readonly isLoggedIn = computed(() => this.user() !== null);

  /** The token the interceptor puts in the Authorization header. */
  getAccessToken(): string | null {
    return this.accessToken();
  }

  /** True if the user has one of the given roles. ADMIN can access everything (matrix 4.4). */
  hasRole(...roles: Role[]): boolean {
    const role = this.user()?.role;
    return !!role && (role === 'ADMIN' || roles.includes(role));
  }

  login(request: LoginRequest): Observable<AuthUser> {
    return this.http.post<TokenPair>(`${environment.apiBaseUrl}/auth/login`, request).pipe(
      tap((tokens) => this.storeTokens(tokens)),
      map(() => this.user()!),
    );
  }

  /**
   * Requests a new accessToken with the refreshToken (POST /auth/refresh).
   * If several requests get a 401 at the same time, they all wait for the same refresh
   * instead of each sending its own.
   */
  refresh(): Observable<string> {
    if (this.refreshInFlight$) {
      return this.refreshInFlight$;
    }
    const refreshToken = readStorage(REFRESH_KEY);
    if (!refreshToken) {
      // Without a refresh token the session cannot be renewed: treated as a rejected refresh.
      return throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Fără refresh token' }));
    }
    const body: RefreshRequest = { refreshToken };

    this.refreshInFlight$ = this.http
      .post<AccessToken>(`${environment.apiBaseUrl}/auth/refresh`, body)
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

  private storeTokens(tokens: TokenPair | AccessToken): void {
    writeStorage(ACCESS_KEY, tokens.accessToken);
    // /auth/login also returns a refreshToken; /auth/refresh only returns an accessToken.
    if ('refreshToken' in tokens && tokens.refreshToken) {
      writeStorage(REFRESH_KEY, tokens.refreshToken);
    }
    this.accessToken.set(tokens.accessToken);
  }
}

/**
 * Reads the `sub` (user) and `role` (single role) claims from the JWT payload, as the contract
 * specifies. Only the backend verifies the signature.
 */
function decodeUser(token: string | null): AuthUser | null {
  if (!token) {
    return null;
  }
  try {
    const payload = JSON.parse(base64UrlDecode(token.split('.')[1]));
    const role = ROLES.includes(payload.role) ? (payload.role as Role) : null;
    return { username: String(payload.sub ?? ''), role };
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

// sessionStorage may throw (private mode, blocked storage): the UI works without it.
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
    /* ignored */
  }
}

function removeStorage(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* ignored */
  }
}
