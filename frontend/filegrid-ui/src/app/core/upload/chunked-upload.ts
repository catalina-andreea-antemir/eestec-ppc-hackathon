import { toApiError } from '../api/api-error';
import { RetryInfo, RetryOptions, throwIfAborted, withRetry } from './retry';
import { ChunkTransport } from './upload-api';
import { FileMetadata, UploadSession } from './upload.models';

/**
 * Strictly sequential chunked upload, per the contract:
 *   POST /uploads -> PUT /uploads/{id}/chunks/{n} (n = 1..totalChunks) -> POST /uploads/{id}/complete
 *
 * - Each chunk is read from the File with `slice` (the file is never loaded into memory).
 * - Transient errors (network, 429, 503, 409 UPLOAD_BUSY) are retried with backoff, for up to 2 minutes.
 * - 409 CHUNK_OUT_OF_ORDER: continue from the chunk the server asks for (`details.expectedChunk`
 *   or, if missing, `nextChunk` from GET /uploads/{id}).
 * - Resending an already received chunk is safe (the server answers `duplicate: true`).
 * - With a given `uploadId`, an existing session is resumed (after a page refresh): GET /uploads/{id}
 *   tells us where to continue.
 */
export interface ChunkedUploadOptions {
  /** Session to resume; absent = new upload. */
  uploadId?: string;
  signal?: AbortSignal;
  retry?: Partial<RetryOptions>;
  /** Called once we have the session (to keep the uploadId in sessionStorage). */
  onSession?: (session: UploadSession) => void;
  onProgress?: (progress: ChunkedProgress) => void;
  onRetry?: (info: RetryInfo, operation: string) => void;
  /** How many consecutive resyncs (CHUNK_OUT_OF_ORDER / UPLOAD_INCOMPLETE) we accept. */
  maxResyncs?: number;
}

export interface ChunkedProgress {
  uploadId: string;
  sentBytes: number;
  totalBytes: number;
  /** Chunk in progress (1-based). */
  chunk: number;
  totalChunks: number;
  phase: 'uploading' | 'completing';
}

/** The re-selected file is not the session's file (different name or size). */
export class FileMismatchError extends Error {
  constructor(readonly session: UploadSession) {
    super(
      `Fișierul ales nu corespunde încărcării începute (${session.fileName}, ${session.totalSize} octeți).`,
    );
    this.name = 'FileMismatchError';
  }
}

/** The session can no longer accept chunks (cancelled, abandoned or missing). */
export class UploadNotActiveError extends Error {
  constructor(readonly session: UploadSession | null) {
    super('Sesiunea de încărcare nu mai este activă; începe o încărcare nouă.');
    this.name = 'UploadNotActiveError';
  }
}

export class ResyncLimitError extends Error {
  constructor() {
    super('Serverul și clientul nu se pun de acord asupra bucății următoare.');
    this.name = 'ResyncLimitError';
  }
}

interface UploadFile {
  readonly name: string;
  readonly size: number;
  slice(start?: number, end?: number, contentType?: string): Blob;
}

export async function runChunkedUpload(
  api: ChunkTransport,
  file: UploadFile,
  options: ChunkedUploadOptions = {},
): Promise<FileMetadata> {
  const { signal } = options;
  const maxResyncs = options.maxResyncs ?? 5;
  const retry = <T>(operation: string, fn: () => Promise<T>) =>
    withRetry(fn, {
      ...options.retry,
      signal,
      onRetry: (info) => options.onRetry?.(info, operation),
    });

  // 1. The session: new or resumed.
  let session = options.uploadId
    ? await retry('stare', () => api.getUpload(options.uploadId!, signal))
    : await retry('inițializare', () =>
        api.initUpload({ category: 'INSPECTION_VIDEO', fileName: file.name, totalSize: file.size }, signal),
      );

  if (session.fileName !== file.name || session.totalSize !== file.size) {
    throw new FileMismatchError(session);
  }
  if (session.status === 'ABORTED' || session.status === 'ABANDONED') {
    throw new UploadNotActiveError(session);
  }
  options.onSession?.(session);

  const id = session.uploadId;
  const { chunkSize, totalChunks } = session;
  const report = (chunk: number, sentBytes: number, phase: ChunkedProgress['phase']) =>
    options.onProgress?.({
      uploadId: id,
      sentBytes: Math.min(sentBytes, file.size),
      totalBytes: file.size,
      chunk: Math.min(chunk, totalChunks),
      totalChunks,
      phase,
    });

  /** The next chunk, as the server sees it (GET /uploads/{id}). */
  const askServer = async () => {
    session = await retry('stare', () => api.getUpload(id, signal));
    if (session.status === 'ABORTED' || session.status === 'ABANDONED') {
      throw new UploadNotActiveError(session);
    }
    return session.nextChunk;
  };

  let n = session.status === 'COMPLETED' ? totalChunks + 1 : session.nextChunk;
  let resyncs = 0;

  for (;;) {
    // 2. The chunks, strictly sequential.
    while (n <= totalChunks) {
      throwIfAborted(signal);
      const start = (n - 1) * chunkSize;
      const end = Math.min(start + chunkSize, file.size);
      const blob = file.slice(start, end, 'application/octet-stream');
      const current = n;
      report(current, start, 'uploading');

      try {
        const ack = await retry(`bucata ${current}`, () =>
          api.putChunk(id, current, blob, signal, (loaded) => report(current, start + loaded, 'uploading')),
        );
        // The server says which one is next; usually current + 1.
        n = ack.nextChunk > current ? ack.nextChunk : current + 1;
        resyncs = 0;
        report(n, (n - 1) * chunkSize, 'uploading');
      } catch (error) {
        const apiError = toApiError(error);
        if (apiError?.code === 'UPLOAD_NOT_ACTIVE') {
          throw new UploadNotActiveError(null);
        }
        if (apiError?.code !== 'CHUNK_OUT_OF_ORDER') {
          throw error;
        }
        if (++resyncs > maxResyncs) {
          throw new ResyncLimitError();
        }
        const expected = Number(apiError.details?.['expectedChunk']);
        n = Number.isInteger(expected) && expected >= 1 ? expected : await askServer();
      }
    }

    // 3. Completion (repeating it on an already completed session returns the same metadata).
    report(totalChunks, file.size, 'completing');
    try {
      return await retry('finalizare', () => api.completeUpload(id, signal));
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError?.code === 'UPLOAD_NOT_ACTIVE') {
        throw new UploadNotActiveError(null);
      }
      if (apiError?.code !== 'UPLOAD_INCOMPLETE' || ++resyncs > maxResyncs) {
        throw apiError?.code === 'UPLOAD_INCOMPLETE' ? new ResyncLimitError() : error;
      }
      const next = Number(apiError.details?.['nextChunk']);
      n = Number.isInteger(next) && next >= 1 ? next : await askServer();
    }
  }
}
