import { HttpClient, HttpEvent, HttpEventType, HttpHeaders, HttpRequest } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { environment } from '../../../environments/environment';
import { abortError } from './retry';
import { ChunkAck, FileMetadata, OutageDetails, UploadInit, UploadSession } from './upload.models';

/** `loadedBytes` = octeți trimiși din corpul cererii; `totalBytes` = mărimea corpului, dacă browserul o știe. */
export type ProgressFn = (loadedBytes: number, totalBytes?: number) => void;

/** Operațiile de upload în bucăți, ca promisiuni care se pot opri cu un AbortSignal. */
export interface ChunkTransport {
  initUpload(init: UploadInit, signal?: AbortSignal): Promise<UploadSession>;
  getUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession>;
  putChunk(uploadId: string, n: number, body: Blob, signal?: AbortSignal, onProgress?: ProgressFn): Promise<ChunkAck>;
  completeUpload(uploadId: string, signal?: AbortSignal): Promise<FileMetadata>;
  abortUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession>;
}

/**
 * Apelurile HTTP pentru upload (contractul: POST /files și /uploads/*).
 * Token-ul și reînnoirea lui le pune authInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class UploadApi implements ChunkTransport {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** POST /files (multipart): OUTAGE_RECORD sau CONNECTION_DOC, până la 20 MB. */
  uploadFile(
    file: File,
    category: 'OUTAGE_RECORD' | 'CONNECTION_DOC',
    outage: OutageDetails | null,
    signal?: AbortSignal,
    onProgress?: ProgressFn,
  ): Promise<FileMetadata> {
    const form = new FormData();
    form.append('category', category);
    form.append('file', file, file.name);
    if (category === 'OUTAGE_RECORD' && outage) {
      form.append('zone', outage.zone);
      form.append('occurredAt', outage.occurredAt);
      if (outage.stationId) {
        form.append('stationId', outage.stationId);
      }
    }
    // Corpul multipart e mai mare decât fișierul (câmpuri, separatori), deci progresul
    // se raportează proporțional cu mărimea fișierului, ca să nu treacă de 100%.
    const fileProgress: ProgressFn | undefined = onProgress
      ? (loaded, total) => onProgress(scaleToFile(loaded, total, file.size))
      : undefined;
    return send(this.http.request(new HttpRequest<FormData>('POST', `${this.base}/files`, form, { reportProgress: true })), signal, fileProgress);
  }

  initUpload(init: UploadInit, signal?: AbortSignal): Promise<UploadSession> {
    return send(this.http.request(new HttpRequest<UploadInit>('POST', `${this.base}/uploads`, init)), signal);
  }

  getUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession> {
    return send(this.http.request(new HttpRequest('GET', this.uploadUrl(uploadId))), signal);
  }

  /** PUT /uploads/{id}/chunks/{n}: corpul e bucata (Blob din File.slice), trimisă direct, fără copiere în memorie. */
  putChunk(uploadId: string, n: number, body: Blob, signal?: AbortSignal, onProgress?: ProgressFn): Promise<ChunkAck> {
    const req = new HttpRequest<Blob>('PUT', `${this.uploadUrl(uploadId)}/chunks/${n}`, body, {
      reportProgress: true,
      headers: new HttpHeaders({ 'Content-Type': 'application/octet-stream' }),
    });
    return send(this.http.request(req), signal, onProgress);
  }

  completeUpload(uploadId: string, signal?: AbortSignal): Promise<FileMetadata> {
    return send(this.http.request(new HttpRequest('POST', `${this.uploadUrl(uploadId)}/complete`, null)), signal);
  }

  abortUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession> {
    return send(this.http.request(new HttpRequest('POST', `${this.uploadUrl(uploadId)}/abort`, null)), signal);
  }

  private uploadUrl(uploadId: string): string {
    return `${this.base}/uploads/${encodeURIComponent(uploadId)}`;
  }
}

/** Octeții din corpul cererii, transformați în octeți din fișier (niciodată peste mărimea lui). */
export function scaleToFile(loaded: number, total: number | undefined, fileSize: number): number {
  if (!total || total <= 0) {
    return Math.min(loaded, fileSize);
  }
  return Math.min(fileSize, Math.round((loaded / total) * fileSize));
}

/**
 * Transformă cererea HTTP într-o promisiune: raportează progresul upload-ului și,
 * la abort, anulează cererea (unsubscribe oprește XHR-ul).
 */
function send<T>(events$: Observable<HttpEvent<T>>, signal?: AbortSignal, onProgress?: ProgressFn): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    let subscription: Subscription | undefined;
    const onAbort = () => {
      subscription?.unsubscribe();
      reject(abortError());
    };
    const cleanup = () => signal?.removeEventListener('abort', onAbort);
    signal?.addEventListener('abort', onAbort, { once: true });

    subscription = events$.subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress) {
          onProgress?.(event.loaded, event.total);
        } else if (event.type === HttpEventType.Response) {
          cleanup();
          resolve(event.body as T);
        }
      },
      error: (error: unknown) => {
        cleanup();
        reject(error);
      },
    });
  });
}
