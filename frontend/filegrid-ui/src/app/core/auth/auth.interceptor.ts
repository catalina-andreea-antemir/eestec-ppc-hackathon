import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

/** Cererile de autentificare nu primesc token și nu declanșează reînnoirea. */
const AUTH_PATHS = ['/auth/login', '/auth/refresh'];

/**
 * 1. Adaugă `Authorization: Bearer <token>` la cererile către API.
 * 2. La 401, reînnoiește token-ul cu /auth/refresh și repetă cererea o singură dată
 *    (un upload de 1,1 GB poate dura mai mult decât cele 15 minute ale token-ului).
 * 3. Dacă reînnoirea eșuează, deloghează utilizatorul și îl trimite la /login.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  if (!isApiRequest(req) || isAuthRequest(req)) {
    return next(req);
  }

  return next(withToken(req, auth.getAccessToken())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        catchError((refreshError: unknown) => {
          // Doar un refresh token respins înseamnă sesiune terminată. La o eroare
          // de rețea utilizatorul rămâne logat, ca upload-ul să se poată relua.
          if (refreshError instanceof HttpErrorResponse && [400, 401, 403].includes(refreshError.status)) {
            auth.logout();
          }
          return throwError(() => refreshError);
        }),
        switchMap((newToken) => next(withToken(req, newToken))),
      );
    }),
  );
};

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

function isApiRequest(req: HttpRequest<unknown>): boolean {
  return req.url.startsWith(environment.apiBaseUrl);
}

function isAuthRequest(req: HttpRequest<unknown>): boolean {
  return AUTH_PATHS.some((path) => req.url.startsWith(environment.apiBaseUrl + path));
}
