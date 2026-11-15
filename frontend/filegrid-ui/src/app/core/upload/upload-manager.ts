import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';

import { toApiError } from '../api/api-error';
import { AuthService } from '../auth/auth.service';
import { FileMismatchError, ResyncLimitError, runChunkedUpload, UploadNotActiveError } from './chunked-upload';
import { httpStatus, isAbortError, isTransientFileError, RetryInfo, RetryTimeoutError, withRetry } from './retry';
import { UploadApi } from './upload-api';
import { CATEGORY_RULES, FileMetadata, ManualCategory, OutageDetails } from './upload.models';

export type UploadPhase = 'idle' | 'uploading' | 'retrying' | 'completing' | 'paused' | 'done' | 'error';

export interface UploadState {
  phase: UploadPhase;
  category: ManualCategory | null;
  fileName: string;
  totalBytes: number;
  sentBytes: number;
  chunk?: number;
  totalChunks?: number;
  /** Bytes per second, averaged since the start of the current attempt. */
  bytesPerSecond?: number;
  /** Message shown on retry or error. */
  message?: string;
  result?: FileMetadata;
}

/** What we keep in sessionStorage so a video upload can resume after a refresh. */
export interface PendingUpload {
  uploadId: string;
  fileName: string;
  totalSize: number;
  category: 'INSPECTION_VIDEO';
  username: string;
  /** Last known value (for display). */
  receivedBytes: number;
}

export const PENDING_UPLOAD_KEY = 'filegrid.pendingUpload';

const IDLE: UploadState = { phase: 'idle', category: null, fileName: '', totalBytes: 0, sentBytes: 0 };

/**
 * Owns the upload in progress. It is an application-wide service, so the upload keeps going
 * even when the user navigates to another page.
 */
@Injectable({ providedIn: 'root' })
export class UploadManager {
  private readonly api = inject(UploadApi);
  private readonly auth = inject(AuthService);

  private readonly stateSignal = signal<UploadState>(IDLE);
  private readonly pendingSignal = signal<PendingUpload | null>(readPending());

  private controller: AbortController | null = null;
  private pausing = false;
  /** The current video file, kept in memory so "Continue" does not require re-selecting it. */
  private readonly currentFile = signal<File | null>(null);
  private rateStart = { time: 0, bytes: 0 };

  readonly state = this.stateSignal.asReadonly();
  readonly busy = computed(() => ['uploading', 'retrying', 'completing'].includes(this.stateSignal().phase));

  /** The current user's unfinished video upload (from sessionStorage). */
  readonly pending = computed(() => {
    const pending = this.pendingSignal();
    return pending && pending.username === this.auth.user()?.username ? pending : null;
  });

  /** True if the in-memory file can continue without being re-selected. */
  readonly canContinueWithoutFile = computed(() => !!this.pending() && !!this.currentFile() && !this.busy());

  /** Starts an upload: POST /files for small files, chunked for videos. */
  async start(file: File, category: ManualCategory, outage: OutageDetails | null = null): Promise<void> {
    if (this.busy()) {
      return;
    }
    if (CATEGORY_RULES[category].chunked) {
      this.currentFile.set(file);
      await this.runVideo(file, undefined);
    } else {
      await this.runSmall(file, category as 'OUTAGE_RECORD' | 'CONNECTION_DOC', outage);
    }
  }

  /**
   * Resumes the saved video upload. Without `file` it uses the in-memory file (after a pause);
   * after a refresh the file must be re-selected and must have the same name and size.
   */
  async resume(file?: File): Promise<void> {
    const pending = this.pending();
    const chosen = file ?? this.currentFile();
    if (this.busy() || !pending || !chosen) {
      return;
    }
    if (chosen.name !== pending.fileName || chosen.size !== pending.totalSize) {
      this.stateSignal.set({
        ...IDLE,
        phase: 'error',
        category: 'INSPECTION_VIDEO',
        fileName: chosen.name,
        totalBytes: chosen.size,
        message: `Alege același fișier: ${pending.fileName} (${pending.totalSize.toLocaleString('ro-RO')} octeți).`,
      });
      return;
    }
    this.currentFile.set(chosen);
    await this.runVideo(chosen, pending.uploadId);
  }

  /** Stops sending; the session stays on the server and in sessionStorage. */
  pause(): void {
    if (this.busy() && this.stateSignal().category === 'INSPECTION_VIDEO') {
      this.pausing = true;
      this.controller?.abort();
    }
  }

  /** Stops and cancels the session on the server (POST /uploads/{id}/abort). */
  async cancel(): Promise<void> {
    this.pausing = false;
    this.controller?.abort();
    const pending = this.pending();
    this.clearPending();
    this.currentFile.set(null);
    this.stateSignal.set({ ...IDLE, message: 'Încărcare anulată.' });
    if (pending) {
      try {
        await withRetry(() => this.api.abortUpload(pending.uploadId), { timeoutMs: 15_000 });
      } catch {
        // Idle sessions are cleaned up on the server after 24 h anyway.
      }
    }
  }

  /** Checks the saved upload on the server (when the page opens, after a refresh). */
  async checkPending(): Promise<void> {
    const pending = this.pending();
    if (!pending || this.busy() || this.stateSignal().phase === 'paused') {
      return;
    }
    try {
      const session = await this.api.getUpload(pending.uploadId);
      if (session.status === 'ABORTED' || session.status === 'ABANDONED') {
        this.clearPending();
      } else {
        this.savePending({ ...pending, receivedBytes: session.receivedBytes });
      }
    } catch (error) {
      if (httpStatus(error) === 404 || httpStatus(error) === 403) {
        this.clearPending();
      }
      // Other errors (network): keep the session; resuming will try again.
    }
  }

  /** Back to the form after a finished upload or an error. */
  reset(): void {
    if (!this.busy()) {
      this.stateSignal.set(IDLE);
    }
  }

  private async runSmall(
    file: File,
    category: 'OUTAGE_RECORD' | 'CONNECTION_DOC',
    outage: OutageDetails | null,
  ): Promise<void> {
    const controller = this.begin(file, category);
    try {
      const result = await withRetry(
        () =>
          this.api.uploadFile(file, category, outage, controller.signal, (loaded) =>
            this.updateProgress({ sentBytes: loaded }),
          ),
        {
          signal: controller.signal,
          isRetriable: isTransientFileError,
          onRetry: (info) => this.showRetry(info, 'fișierul'),
        },
      );
      this.finish(result);
    } catch (error) {
      this.fail(error);
    }
  }

  private async runVideo(file: File, uploadId: string | undefined): Promise<void> {
    const controller = this.begin(file, 'INSPECTION_VIDEO');
    try {
      const result = await runChunkedUpload(this.api, file, {
        uploadId,
        signal: controller.signal,
        onSession: (session) =>
          this.savePending({
            uploadId: session.uploadId,
            fileName: session.fileName,
            totalSize: session.totalSize,
            category: 'INSPECTION_VIDEO',
            username: this.auth.user()?.username ?? '',
            receivedBytes: session.receivedBytes,
          }),
        onProgress: (p) =>
          this.updateProgress({
            phase: p.phase,
            sentBytes: p.sentBytes,
            chunk: p.chunk,
            totalChunks: p.totalChunks,
          }),
        onRetry: (info, operation) => this.showRetry(info, operation),
      });
      this.clearPending();
      this.currentFile.set(null);
      this.finish(result);
    } catch (error) {
      if (error instanceof UploadNotActiveError || error instanceof FileMismatchError || httpStatus(error) === 404) {
        this.clearPending();
      }
      this.fail(error);
    }
  }

  private begin(file: File, category: ManualCategory): AbortController {
    this.controller?.abort();
    this.controller = new AbortController();
    this.pausing = false;
    this.rateStart = { time: 0, bytes: 0 };
    this.stateSignal.set({
      ...IDLE,
      phase: 'uploading',
      category,
      fileName: file.name,
      totalBytes: file.size,
    });
    return this.controller;
  }

  private updateProgress(update: Partial<UploadState>): void {
    const now = Date.now();
    if (this.rateStart.time === 0) {
      this.rateStart = { time: now, bytes: update.sentBytes ?? 0 };
    }
    this.stateSignal.update((state) => {
      const sentBytes = update.sentBytes ?? state.sentBytes;
      const elapsed = (now - this.rateStart.time) / 1000;
      return {
        ...state,
        ...update,
        phase: update.phase ?? 'uploading',
        message: undefined,
        bytesPerSecond: elapsed >= 1 ? (sentBytes - this.rateStart.bytes) / elapsed : state.bytesPerSecond,
      };
    });
    const pending = this.pending();
    if (pending && update.sentBytes !== undefined && update.phase) {
      // Update rarely (once per chunk), not on every progress event.
      if (update.sentBytes - pending.receivedBytes >= 16 * 1024 * 1024 || update.phase === 'completing') {
        this.savePending({ ...pending, receivedBytes: update.sentBytes });
      }
    }
  }

  private showRetry(info: RetryInfo, operation: string): void {
    const seconds = Math.max(1, Math.round(info.delayMs / 1000));
    this.rateStart = { time: 0, bytes: 0 };
    // If the server sent a Romanian message (Error schema), show it: it states the real reason.
    const reason = toApiError(info.error)?.message ?? `${retryReason(info.error)} la ${operation}.`;
    this.stateSignal.update((state) => ({
      ...state,
      phase: 'retrying',
      message: `${reason} Reîncerc în ${seconds} s (reîncercarea ${info.attempt}).`,
    }));
  }

  private finish(result: FileMetadata): void {
    this.controller = null;
    this.stateSignal.update((state) => ({
      ...state,
      phase: 'done',
      sentBytes: state.totalBytes,
      message: undefined,
      result,
    }));
  }

  private fail(error: unknown): void {
    this.controller = null;
    if (isAbortError(error)) {
      const paused = this.pausing;
      this.pausing = false;
      if (paused) {
        this.stateSignal.update((state) => ({ ...state, phase: 'paused', message: undefined, bytesPerSecond: undefined }));
      }
      return;
    }
    this.stateSignal.update((state) => ({
      ...state,
      phase: 'error',
      bytesPerSecond: undefined,
      message: describeError(error, !!this.pending()),
    }));
  }

  private savePending(pending: PendingUpload): void {
    this.pendingSignal.set(pending);
    try {
      sessionStorage.setItem(PENDING_UPLOAD_KEY, JSON.stringify(pending));
    } catch {
      /* without sessionStorage, resuming only works without a refresh */
    }
  }

  private clearPending(): void {
    this.pendingSignal.set(null);
    try {
      sessionStorage.removeItem(PENDING_UPLOAD_KEY);
    } catch {
      /* ignored */
    }
  }
}

function readPending(): PendingUpload | null {
  try {
    const raw = sessionStorage.getItem(PENDING_UPLOAD_KEY);
    const value = raw ? (JSON.parse(raw) as PendingUpload) : null;
    return value && typeof value.uploadId === 'string' ? value : null;
  } catch {
    return null;
  }
}

function retryReason(error: unknown): string {
  const status = httpStatus(error);
  const code = toApiError(error)?.code;
  if (status === 0) {
    return 'Conexiune întreruptă';
  }
  if (code === 'UPLOAD_BUSY') {
    return 'Sesiune ocupată de altă cerere';
  }
  if (status === 429) {
    return 'Server ocupat (limita categoriei)';
  }
  if (status === 503) {
    return 'Stocare indisponibilă';
  }
  return `Eroare de server (${status})`;
}

/** Romanian user-facing message. */
export function describeError(error: unknown, resumable: boolean): string {
  if (error instanceof RetryTimeoutError) {
    return resumable
      ? 'Conexiunea nu și-a revenit în 2 minute. Încărcarea e păstrată: apasă „Continuă” când rețeaua merge.'
      : 'Serverul nu a răspuns în 2 minute. Încearcă din nou.';
  }
  if (
    error instanceof FileMismatchError ||
    error instanceof UploadNotActiveError ||
    error instanceof ResyncLimitError
  ) {
    return error.message;
  }
  if (error instanceof HttpErrorResponse) {
    const apiMessage = toApiError(error)?.message;
    switch (error.status) {
      case 400:
        return apiMessage ?? 'Date invalide.';
      case 403:
        return 'Rolul tău nu are voie să încarce această categorie.';
      case 404:
        return 'Sesiunea de încărcare nu mai există pe server; începe o încărcare nouă.';
      case 413:
        return 'Fișierul depășește mărimea maximă permisă pentru categorie.';
    }
    return apiMessage ?? `Eroare neașteptată (${error.status}).`;
  }
  return 'A apărut o eroare neașteptată.';
}
