import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from '../api/api-error';

/**
 * Retry with exponential backoff, up to a timeout (default 2 minutes from the first failure).
 *
 * - If the server says how long to wait (`Retry-After` or `retryAfterSeconds`, on 429, 503 and
 *   409 UPLOAD_BUSY), we wait exactly that long.
 * - Otherwise: 1 s, 2 s, 4 s, 8 s, then 10 s (cap), with +-20% jitter so many clients do not all
 *   retry in the same second.
 * - On a network error the wait is cut short when the browser reports it is back online.
 */
export interface RetryOptions {
  /** How long we keep retrying, from the first failure. */
  timeoutMs: number;
  baseDelayMs: number;
  maxDelayMs: number;
  /** Which errors are worth retrying (isTransientError by default). */
  isRetriable: (error: unknown) => boolean;
  signal?: AbortSignal;
  onRetry?: (info: RetryInfo) => void;
  // Injectable for tests.
  now: () => number;
  sleep: (ms: number, signal?: AbortSignal, wakeOnOnline?: boolean) => Promise<void>;
  random: () => number;
}

export interface RetryInfo {
  /** Retry number (1 = first retry). */
  attempt: number;
  delayMs: number;
  error: unknown;
  /** true if the delay came from the server (Retry-After). */
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

/** Thrown when retries exceed the timeout; `cause` is the last error. */
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

/** 1 s, 2 s, 4 s, 8 s, ... capped at maxDelayMs, with +-20% jitter. */
export function backoffMs(attempt: number, opts: Pick<RetryOptions, 'baseDelayMs' | 'maxDelayMs' | 'random'>): number {
  const exponential = Math.min(opts.maxDelayMs, opts.baseDelayMs * 2 ** (attempt - 1));
  return Math.round(exponential * (0.8 + 0.4 * opts.random()));
}

/** The delay requested by the server: `retryAfterSeconds` in the body or the `Retry-After` header (seconds). */
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
 * Transient errors, for operations that are safe to repeat (chunks, GET, complete, abort):
 * network down (status 0), 408, 429, 5xx and 409 UPLOAD_BUSY (lease taken).
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
 * For POST /files (the contract has no Idempotency-Key for it): retry only when the server did not
 * keep the file (429, 503) or when we cannot tell (network, 502, 504). In the latter case there is
 * a small risk of a duplicate, which beats losing an outage record.
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
  return new DOMException('Încărcare oprită.', 'AbortError');
}

export function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw abortError();
  }
}

/** Waits `ms`; stops on abort and, optionally, earlier when the browser comes back online. */
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
      // One second of grace: the network has only just come back.
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
