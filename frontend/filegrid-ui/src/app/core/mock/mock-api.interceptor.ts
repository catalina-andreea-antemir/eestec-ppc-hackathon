import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { delay, Observable, of, switchMap, throwError, timer } from 'rxjs';

import { environment } from '../../../environments/environment';
import { LoginRequest, RefreshRequest, Role, TokenResponse } from '../auth/auth.models';

/**
 * Backend fals pentru `ng serve`, activ doar când environment.useMocks e true.
 * Imită contractul din docs/api/openapi.yaml până există API-ul real.
 *
 * Utilizatori de test: numele de mai jos, cu orice parolă nevidă
 * (nu ținem parole în repo; parolele reale sunt generate de scripts/start.* în .env).
 */
const MOCK_USERS: Record<string, Role[]> = {
  dispecer: ['DISPATCH'],
  teren: ['FIELD'],
  racordari: ['CONNECTIONS'],
  contor: ['METER_SERVICE'],
  admin: ['ADMIN'],
};

/**
 * Contractul spune 15 minute. În mock e mai scurt, ca reînnoirea prin
 * /auth/refresh să poată fi văzută repede în Network.
 */
const ACCESS_TTL_SECONDS = 120;
const REFRESH_TTL_SECONDS = 8 * 60 * 60;
const LATENCY_MS = 300;

export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!environment.useMocks || !req.url.startsWith(environment.apiBaseUrl)) {
    return next(req);
  }
  const path = req.url.substring(environment.apiBaseUrl.length).split('?')[0];

  if (req.method === 'POST' && path === '/auth/login') {
    return login(req as HttpRequest<LoginRequest>);
  }
  if (req.method === 'POST' && path === '/auth/refresh') {
    return refresh(req as HttpRequest<RefreshRequest>);
  }
  if (req.method === 'GET' && path === '/health') {
    return ok({ status: 'UP' });
  }

  // Orice alt endpoint cere un accessToken valid, ca în backend-ul real.
  if (!isValidToken(bearerToken(req), 'access')) {
    return error(401, 'Token lipsă sau expirat');
  }
  return error(404, `Endpoint-ul ${req.method} ${path} nu are încă mock`);
};

function login(req: HttpRequest<LoginRequest>): Observable<HttpResponse<TokenResponse>> {
  const username = req.body?.username?.trim().toLowerCase() ?? '';
  const roles = MOCK_USERS[username];
  if (!roles || !req.body?.password) {
    return error(401, 'Utilizator sau parolă greșită');
  }
  return ok(issueTokens(username, roles));
}

function refresh(req: HttpRequest<RefreshRequest>): Observable<HttpResponse<TokenResponse>> {
  const token = req.body?.refreshToken ?? '';
  if (!isValidToken(token, 'refresh')) {
    return error(401, 'Refresh token invalid sau expirat');
  }
  const payload = decode(token)!;
  const { refreshToken: _unchanged, ...rest } = issueTokens(payload.sub, payload.roles);
  return ok(rest);
}

interface MockPayload {
  sub: string;
  roles: Role[];
  typ: 'access' | 'refresh';
  exp: number;
}

function issueTokens(username: string, roles: Role[]): TokenResponse {
  const now = Math.floor(Date.now() / 1000);
  return {
    accessToken: encode({ sub: username, roles, typ: 'access', exp: now + ACCESS_TTL_SECONDS }),
    refreshToken: encode({ sub: username, roles, typ: 'refresh', exp: now + REFRESH_TTL_SECONDS }),
    expiresIn: ACCESS_TTL_SECONDS,
  };
}

/** Token cu forma unui JWT (header.payload.semnătură), dar nesemnat: doar pentru mock. */
function encode(payload: MockPayload): string {
  const part = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${part({ alg: 'none', typ: 'JWT' })}.${part(payload)}.mock`;
}

function decode(token: string): MockPayload | null {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64)) as MockPayload;
  } catch {
    return null;
  }
}

function isValidToken(token: string | null, typ: MockPayload['typ']): boolean {
  const payload = token ? decode(token) : null;
  return !!payload && payload.typ === typ && payload.exp > Date.now() / 1000;
}

function bearerToken(req: HttpRequest<unknown>): string | null {
  const header = req.headers.get('Authorization');
  return header?.startsWith('Bearer ') ? header.substring(7) : null;
}

function ok<T>(body: T): Observable<HttpResponse<T>> {
  return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function error(status: number, message: string): Observable<never> {
  // `delay` nu întârzie erorile, deci așteptăm întâi cu `timer`.
  return timer(LATENCY_MS).pipe(
    switchMap(() =>
      throwError(() => new HttpErrorResponse({ status, statusText: message, error: { message } })),
    ),
  );
}
