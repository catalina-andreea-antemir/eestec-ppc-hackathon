import { HttpClient, HttpEvent, HttpEventType, HttpHeaders, HttpRequest } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { environment } from '../../../environments/environment';
import { abortError } from './retry';
import { ChunkAck, FileMetadata, OutageDetails, UploadInit, UploadSession } from './upload.models';

/** `loadedBytes` = bytes sent from the request body; `totalBytes` = body size, if the browser knows it. */
export type ProgressFn = (loadedBytes: number, totalBytes?: number) => void;

/** Chunked upload operations, as promises that can be stopped with an AbortSignal. */
export interface ChunkTransport {
  initUpload(init: UploadInit, signal?: AbortSignal): Promise<UploadSession>;
  getUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession>;
  putChunk(uploadId: string, n: number, body: Blob, signal?: AbortSignal, onProgress?: ProgressFn): Promise<ChunkAck>;
  completeUpload(uploadId: string, signal?: AbortSignal): Promise<FileMetadata>;
  abortUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession>;
}

/**
 * HTTP calls for uploads (contract: POST /files and /uploads/*).
 * authInterceptor adds the token and handles its refresh.
 */
@Injectable({ providedIn: 'root' })
export class UploadApi implements ChunkTransport {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** POST /files (multipart): OUTAGE_RECORD or CONNECTION_DOC, up to 20 MB. */
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
    // The multipart body is larger than the file (fields, boundaries), so progress is
    // reported relative to the file size so it never goes past 100%.
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

  /** PUT /uploads/{id}/chunks/{n}: the body is the chunk (Blob from File.slice), sent directly, no in-memory copy. */
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

/** Request body bytes translated into file bytes (never above the file size). */
export function scaleToFile(loaded: number, total: number | undefined, fileSize: number): number {
  if (!total || total <= 0) {
    return Math.min(loaded, fileSize);
  }
  return Math.min(fileSize, Math.round((loaded / total) * fileSize));
}

/**
 * Turns the HTTP request into a promise: reports upload progress and,
 * on abort, cancels the request (unsubscribe stops the XHR).
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
