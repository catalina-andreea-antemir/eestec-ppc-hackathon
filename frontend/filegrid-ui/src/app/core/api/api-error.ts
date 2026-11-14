import { HttpErrorResponse } from '@angular/common/http';

/** Formatul unic al erorilor din contract (docs/api/openapi.yaml, schema Error). */
export interface ApiError {
  status: number;
  /** Cod stabil, de exemplu VALIDATION_ERROR, UNAUTHORIZED, CHUNK_OUT_OF_ORDER, STORAGE_UNAVAILABLE. */
  code: string;
  /** Mesaj în română, fără date personale. */
  message: string;
  traceId: string;
  /** Doar la 429/503 și UPLOAD_BUSY; același cu header-ul Retry-After. */
  retryAfterSeconds?: number;
  details?: Record<string, unknown>;
}

/** Extrage ApiError din răspunsul HTTP, dacă serverul a trimis formatul din contract. */
export function toApiError(error: unknown): ApiError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const body = error.error as Partial<ApiError> | null;
  if (body && typeof body === 'object' && typeof body.code === 'string') {
    return {
      status: body.status ?? error.status,
      code: body.code,
      message: body.message ?? error.statusText,
      traceId: body.traceId ?? error.headers.get('X-Trace-Id') ?? '',
      retryAfterSeconds: body.retryAfterSeconds,
      details: body.details,
    };
  }
  return null;
}
