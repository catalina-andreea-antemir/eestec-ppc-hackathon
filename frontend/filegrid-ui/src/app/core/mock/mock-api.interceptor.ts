import { HttpErrorResponse, HttpHeaders, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { delay, Observable, of, switchMap, throwError, timer } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ApiError } from '../api/api-error';
import { AccessToken, LoginRequest, RefreshRequest, Role, TokenPair } from '../auth/auth.models';
import { handleUploadRoutes } from './mock-uploads';

/**
 * Backend fals pentru `ng serve`, activ doar când environment.useMocks e true.
 * Imită contractul din docs/api/openapi.yaml până există API-ul real.
 *
 * Utilizatori de test: numele de mai jos, opțional cu cifre la final (teren1, racordari2),
 * cu orice parolă nevidă. Nu ținem parole în repo; parolele reale sunt generate de
 * scripts/start.* în .env.
 */
const MOCK_USERS: Record<string, Role> = {
  dispecer: 'DISPATCH',
  teren: 'FIELD',
  racordari: 'CONNECTIONS',
  contor: 'METER_SERVICE',
  admin: 'ADMIN',
};

/**
 * Contractul spune 15 minute. În mock e mai scurt, ca reînnoirea prin
 * /auth/refresh să poată fi văzută repede.
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
  const token = bearerToken(req);
  if (!isValidToken(token, 'access')) {
    return error(401, 'UNAUTHORIZED', 'Token lipsă, expirat sau invalid.');
  }
  const { sub, role } = decode(token!)!;
  const uploads = handleUploadRoutes(req, path, { sub, role });
  if (uploads) {
    return uploads;
  }
  return error(404, 'NOT_FOUND', `Endpoint-ul ${req.method} ${path} nu are încă mock.`);
};

function login(req: HttpRequest<LoginRequest>): Observable<HttpResponse<TokenPair>> {
  const username = req.body?.username?.trim().toLowerCase() ?? '';
  if (!username || !req.body?.password) {
    return error(400, 'VALIDATION_ERROR', 'Utilizatorul și parola sunt obligatorii.');
  }
  const role = MOCK_USERS[username.replace(/\d+$/, '')];
  if (!role) {
    return error(401, 'UNAUTHORIZED', 'Utilizator sau parolă greșită.');
  }
  return ok(issueTokens(username, role));
}

function refresh(req: HttpRequest<RefreshRequest>): Observable<HttpResponse<AccessToken>> {
  const token = req.body?.refreshToken ?? '';
  if (!isValidToken(token, 'refresh')) {
    return error(401, 'UNAUTHORIZED', 'Refresh token invalid sau expirat.');
  }
  const payload = decode(token)!;
  const { accessToken, expiresIn } = issueTokens(payload.sub, payload.role);
  return ok({ accessToken, expiresIn });
}

interface MockPayload {
  sub: string;
  role: Role;
  typ: 'access' | 'refresh';
  exp: number;
}

function issueTokens(username: string, role: Role): TokenPair {
  const now = Math.floor(Date.now() / 1000);
  return {
    accessToken: encode({ sub: username, role, typ: 'access', exp: now + ACCESS_TTL_SECONDS }),
    refreshToken: encode({ sub: username, role, typ: 'refresh', exp: now + REFRESH_TTL_SECONDS }),
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

function traceId(): string {
  return crypto.randomUUID().replace(/-/g, '');
}

function ok<T>(body: T): Observable<HttpResponse<T>> {
  const headers = new HttpHeaders({ 'X-Trace-Id': traceId() });
  return of(new HttpResponse({ status: 200, body, headers })).pipe(delay(LATENCY_MS));
}

/** Eroare în formatul Error din contract, cu header-ul X-Trace-Id. */
function error(status: number, code: string, message: string): Observable<never> {
  const id = traceId();
  const body: ApiError = { status, code, message, traceId: id };
  const headers = new HttpHeaders({ 'X-Trace-Id': id });
  // `delay` nu întârzie erorile, deci așteptăm întâi cu `timer`.
  return timer(LATENCY_MS).pipe(
    switchMap(() =>
      throwError(() => new HttpErrorResponse({ status, statusText: message, error: body, headers })),
    ),
  );
}
