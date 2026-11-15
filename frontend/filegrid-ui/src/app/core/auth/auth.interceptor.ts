import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

/** Auth requests get no token and never trigger a refresh. */
const AUTH_PATHS = ['/auth/login', '/auth/refresh'];

/**
 * 1. Adds `Authorization: Bearer <token>` to API requests.
 * 2. On 401, refreshes the token via /auth/refresh and retries the request once
 *    (a 1.1 GB upload can outlast the token's 15 minutes).
 * 3. If the refresh fails, logs the user out and sends them to /login.
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
          // Only a rejected refresh token means the session is over. On a network
          // error the user stays logged in so the upload can resume.
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
