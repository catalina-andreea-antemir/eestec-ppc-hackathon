import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from '../api/api-error';

/**
 * Reîncercare cu backoff exponențial, până la un timeout (implicit 2 minute de la primul eșec).
 *
 * - Dacă serverul spune cât să așteptăm (`Retry-After` sau `retryAfterSeconds`, la 429, 503 și
 *   409 UPLOAD_BUSY), așteptăm exact atât.
 * - Altfel: 1 s, 2 s, 4 s, 8 s, apoi 10 s (plafon), cu ±20% variație ca mai mulți clienți să nu
 *   reîncerce toți în aceeași secundă.
 * - La o eroare de rețea, așteptarea se scurtează când browserul anunță că a revenit online.
 */
export interface RetryOptions {
  /** Cât timp reîncercăm, de la primul eșec. */
  timeoutMs: number;
  baseDelayMs: number;
  maxDelayMs: number;
  /** Ce erori merită reîncercate (implicit isTransientError). */
  isRetriable: (error: unknown) => boolean;
  signal?: AbortSignal;
  onRetry?: (info: RetryInfo) => void;
  // Injectabile pentru teste.
  now: () => number;
  sleep: (ms: number, signal?: AbortSignal, wakeOnOnline?: boolean) => Promise<void>;
  random: () => number;
}

export interface RetryInfo {
  /** Numărul reîncercării (1 = prima reîncercare). */
  attempt: number;
  delayMs: number;
  error: unknown;
  /** true dacă întârzierea vine de la server (Retry-After). */
  serverHint: boolean;
}

export const DEFAULT_RETRY: RetryOptions = {
  timeoutMs: 2 * 60 * 1000,
  baseDelayMs: 1000,
  maxDelayMs: 10_000,
  isRetriable: isTransientError,
  now: () => Date.now(),
  sleep: abortableSleep,
  random: Math.random,
};

/** Aruncată când reîncercările au depășit timeout-ul; `cause` este ultima eroare. */
export class RetryTimeoutError extends Error {
  constructor(
    readonly lastError: unknown,
    readonly attempts: number,
  ) {
    super(`Operația a eșuat după ${attempts} reîncercări.`);
    this.name = 'RetryTimeoutError';
  }
}

export async function withRetry<T>(operation: () => Promise<T>, options: Partial<RetryOptions> = {}): Promise<T> {
  const opts: RetryOptions = { ...DEFAULT_RETRY, ...options };
  let firstFailureAt: number | null = null;
  let attempt = 0;

  for (;;) {
    throwIfAborted(opts.signal);
    try {
      return await operation();
    } catch (error) {
      if (isAbortError(error) || !opts.isRetriable(error)) {
        throw error;
      }
      firstFailureAt ??= opts.now();
      attempt++;
      const hint = retryAfterMs(error);
      const delayMs = hint ?? backoffMs(attempt, opts);
      if (opts.now() - firstFailureAt + delayMs > opts.timeoutMs) {
        throw new RetryTimeoutError(error, attempt - 1);
      }
      opts.onRetry?.({ attempt, delayMs, error, serverHint: hint !== null });
      await opts.sleep(delayMs, opts.signal, httpStatus(error) === 0);
    }
  }
}

/** 1 s, 2 s, 4 s, 8 s, ... plafonat la maxDelayMs, cu ±20% variație. */
export function backoffMs(attempt: number, opts: Pick<RetryOptions, 'baseDelayMs' | 'maxDelayMs' | 'random'>): number {
  const exponential = Math.min(opts.maxDelayMs, opts.baseDelayMs * 2 ** (attempt - 1));
  return Math.round(exponential * (0.8 + 0.4 * opts.random()));
}

/** Întârzierea cerută de server: `retryAfterSeconds` din corp sau header-ul `Retry-After` (secunde). */
export function retryAfterMs(error: unknown): number | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const fromBody = toApiError(error)?.retryAfterSeconds;
  if (typeof fromBody === 'number' && fromBody >= 0) {
    return fromBody * 1000;
  }
  const header = Number.parseInt(error.headers?.get('Retry-After') ?? '', 10);
  return Number.isFinite(header) && header >= 0 ? header * 1000 : null;
}

/**
 * Erori trecătoare, pentru operații sigure la repetare (bucăți, GET, complete, abort):
 * rețea căzută (status 0), 408, 429, 5xx și 409 UPLOAD_BUSY (lease ocupat).
 */
export function isTransientError(error: unknown): boolean {
  const status = httpStatus(error);
  if (status === null) {
    return false;
  }
  if (status === 409) {
    return toApiError(error)?.code === 'UPLOAD_BUSY';
  }
  return status === 0 || status === 408 || status === 429 || (status >= 500 && status !== 501);
}

/**
 * Pentru POST /files (fără Idempotency-Key în contract): reîncercăm doar când serverul n-a
 * păstrat fișierul (429, 503) sau când nu știm (rețea, 502, 504). În ultimul caz există
 * un risc mic de duplicat, preferabil pierderii unei înregistrări de avarie.
 */
export function isTransientFileError(error: unknown): boolean {
  const status = httpStatus(error);
  return status !== null && [0, 429, 502, 503, 504].includes(status);
}

export function httpStatus(error: unknown): number | null {
  return error instanceof HttpErrorResponse ? error.status : null;
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export function abortError(): DOMException {
  return new DOMException('Upload oprit.', 'AbortError');
}

export function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw abortError();
  }
}

/** Așteaptă `ms`; se oprește la abort și, opțional, mai devreme când browserul revine online. */
export function abortableSleep(ms: number, signal?: AbortSignal, wakeOnOnline = false): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const done = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
      if (wakeOnOnline) {
        globalThis.removeEventListener?.('online', onOnline);
      }
    };
    const onAbort = () => {
      done();
      reject(abortError());
    };
    const onOnline = () => {
      done();
      // O secundă de răgaz: rețeaua abia a revenit.
      setTimeout(resolve, 1000);
    };
    const timer = setTimeout(() => {
      done();
      resolve();
    }, ms);
    signal?.addEventListener('abort', onAbort, { once: true });
    if (wakeOnOnline) {
      globalThis.addEventListener?.('online', onOnline, { once: true });
    }
  });
}
