import { HttpErrorResponse } from '@angular/common/http';

/** The contract's single error format (docs/api/openapi.yaml, schema Error). */
export interface ApiError {
  status: number;
  /** Stable code, e.g. VALIDATION_ERROR, UNAUTHORIZED, CHUNK_OUT_OF_ORDER, STORAGE_UNAVAILABLE. */
  code: string;
  /** Message in Romanian (shown as-is in the UI), never containing personal data. */
  message: string;
  traceId: string;
  /** Only on 429/503 and UPLOAD_BUSY; same value as the Retry-After header. */
  retryAfterSeconds?: number;
  details?: Record<string, unknown>;
}

/** Extracts an ApiError from the HTTP response, if the server sent the contract format. */
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
