import { toApiError } from '../api/api-error';
import { RetryInfo, RetryOptions, throwIfAborted, withRetry } from './retry';
import { ChunkTransport } from './upload-api';
import { FileMetadata, UploadSession } from './upload.models';

/**
 * Upload în bucăți, strict secvențial, după contract:
 *   POST /uploads → PUT /uploads/{id}/chunks/{n} (n = 1..totalChunks) → POST /uploads/{id}/complete
 *
 * - Fiecare bucată se citește din File cu `slice` (fără să încărcăm fișierul în memorie).
 * - Erorile trecătoare (rețea, 429, 503, 409 UPLOAD_BUSY) se reîncearcă cu backoff, până la 2 minute.
 * - 409 CHUNK_OUT_OF_ORDER: continuăm de la bucata cerută de server (`details.expectedChunk`
 *   sau, dacă lipsește, `nextChunk` din GET /uploads/{id}).
 * - Retrimiterea unei bucăți deja primite e sigură (serverul răspunde `duplicate: true`).
 * - Cu `uploadId` dat, se reia o sesiune existentă (după refresh de pagină): GET /uploads/{id}
 *   spune de unde continuăm.
 */
export interface ChunkedUploadOptions {
  /** Sesiune de reluat; lipsă = upload nou. */
  uploadId?: string;
  signal?: AbortSignal;
  retry?: Partial<RetryOptions>;
  /** Apelat când avem sesiunea (pentru a păstra uploadId-ul în sessionStorage). */
  onSession?: (session: UploadSession) => void;
  onProgress?: (progress: ChunkedProgress) => void;
  onRetry?: (info: RetryInfo, operation: string) => void;
  /** Câte resincronizări (CHUNK_OUT_OF_ORDER / UPLOAD_INCOMPLETE) la rând acceptăm. */
  maxResyncs?: number;
}

export interface ChunkedProgress {
  uploadId: string;
  sentBytes: number;
  totalBytes: number;
  /** Bucata în curs (de la 1). */
  chunk: number;
  totalChunks: number;
  phase: 'uploading' | 'completing';
}

/** Fișierul re-selectat nu e cel din sesiune (nume sau mărime diferite). */
export class FileMismatchError extends Error {
  constructor(readonly session: UploadSession) {
    super(
      `Fișierul ales nu corespunde upload-ului început (${session.fileName}, ${session.totalSize} octeți).`,
    );
    this.name = 'FileMismatchError';
  }
}

/** Sesiunea nu mai poate primi bucăți (anulată, abandonată sau inexistentă). */
export class UploadNotActiveError extends Error {
  constructor(readonly session: UploadSession | null) {
    super('Sesiunea de upload nu mai este activă; începe un upload nou.');
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

  // 1. Sesiunea: nouă sau reluată.
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

  /** Bucata următoare, după cum o vede serverul (GET /uploads/{id}). */
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
    // 2. Bucățile, strict secvențial.
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
        // Serverul spune care e următoarea; de obicei current + 1.
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

    // 3. Finalizarea (repetată pe o sesiune deja finalizată întoarce aceleași metadate).
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
