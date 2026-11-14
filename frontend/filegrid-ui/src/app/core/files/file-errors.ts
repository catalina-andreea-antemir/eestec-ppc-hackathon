import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from '../api/api-error';
import { LinkExpiredError } from './file-downloader';

/** Rezultatul afișat după „Verifică integritatea”. */
export type VerifyOutcome =
  | { kind: 'ok'; expectedSha256: string; actualSha256: string; deep: boolean; plainOk: boolean | null; verifiedAt: string }
  | { kind: 'corrupt'; expectedSha256: string | null; actualSha256: string | null; message: string; traceId: string }
  | { kind: 'error'; message: string };

/** 409 FILE_CORRUPT → „Corupt” (cu hash-urile din details); orice altă eroare → mesaj. */
export function verifyErrorOutcome(error: unknown): VerifyOutcome {
  const api = toApiError(error);
  if (api?.status === 409 && api.code === 'FILE_CORRUPT') {
    const details = api.details ?? {};
    return {
      kind: 'corrupt',
      expectedSha256: stringOrNull(details['expectedSha256'] ?? details['expectedSha256Plain']),
      actualSha256: stringOrNull(details['actualSha256'] ?? details['actualSha256Plain']),
      message: api.message || 'fișier corupt',
      traceId: api.traceId,
    };
  }
  return { kind: 'error', message: describeFileError(error) };
}

/** Mesaj în română pentru erorile de la /files/*, după codurile din contract. */
export function describeFileError(error: unknown): string {
  if (error instanceof LinkExpiredError) {
    return error.message;
  }
  const api = toApiError(error);
  const status = api?.status ?? (error instanceof HttpErrorResponse ? error.status : -1);
  const trace = api?.traceId ? ` (trace ${api.traceId})` : '';
  switch (status) {
    case 0:
      return 'Serverul nu răspunde (rețea căzută sau API oprit). Încearcă din nou.';
    case 400:
      return `Filtre invalide: ${api?.message ?? 'cererea nu respectă contractul'}${trace}`;
    case 401:
      return 'Sesiunea sau link-ul a expirat. Autentifică-te din nou ori apasă iar „Descarcă” pentru un link nou.';
    case 403:
      return `Rolul tău nu are acces la această categorie (matricea de roluri).${trace}`;
    case 404:
      return `Fișierul nu există (sau conținutul lui nu mai e disponibil).${trace}`;
    case 405:
    case 501:
      // Endpoint-ul nu e (încă) implementat pe server, de exemplu GET /files înainte de C8.
      return 'Funcția aceasta nu este încă disponibilă pe server.';
    case 409:
      return `${api?.message ?? 'Conflict'}${trace}`;
    case 429:
    case 503:
      return `Serviciul e ocupat sau un storage e indisponibil; reîncearcă${api?.retryAfterSeconds ? ` peste ${api.retryAfterSeconds} s` : ''}.${trace}`;
    default:
      return api?.message ? `${api.message}${trace}` : `Eroare neașteptată (${status}).`;
  }
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}
