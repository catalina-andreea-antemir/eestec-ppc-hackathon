import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FileDownloader } from '../../core/files/file-downloader';
import { describeFileError, VerifyOutcome, verifyErrorOutcome } from '../../core/files/file-errors';
import { FilesApi } from '../../core/files/files-api';
import { FileMetadata } from '../../core/files/files.models';

/**
 * Butoanele „Descarcă” și „Verifică integritatea” pentru un fișier (F5).
 *
 * - Descarcă: cere un link semnat și îl lasă pe browser să descarce nativ (vezi FileDownloader).
 * - Verifică: serverul recitește fișierul din storage și recalculează SHA-256; 200 → „OK” verde,
 *   409 FILE_CORRUPT → „Corupt” roșu, cu hash-ul așteptat și cel găsit.
 *   La documente de racordare se poate cere și verificarea completă (deep: decriptează și compară
 *   și hash-ul originalului).
 */
@Component({
  selector: 'app-file-actions',
  imports: [DatePipe],
  templateUrl: './file-actions.html',
  styleUrl: './file-actions.scss',
})
export class FileActions {
  private readonly api = inject(FilesApi);
  private readonly downloader = inject(FileDownloader);

  readonly file = input.required<FileMetadata>();

  protected readonly deep = signal(false);
  protected readonly downloading = signal(false);
  protected readonly downloadInfo = signal<{ expiresAt: string } | null>(null);
  protected readonly downloadError = signal<string | null>(null);
  protected readonly verifying = signal(false);
  protected readonly outcome = signal<VerifyOutcome | null>(null);

  async download(): Promise<void> {
    this.downloading.set(true);
    this.downloadError.set(null);
    this.downloadInfo.set(null);
    try {
      const link = await this.downloader.download(this.file());
      this.downloadInfo.set({ expiresAt: link.expiresAt });
    } catch (error) {
      this.downloadError.set(describeFileError(error));
    } finally {
      this.downloading.set(false);
    }
  }

  async verify(): Promise<void> {
    const file = this.file();
    const deep = file.category === 'CONNECTION_DOC' && this.deep();
    this.verifying.set(true);
    this.outcome.set(null);
    try {
      const result = await firstValueFrom(this.api.verify(file.id, deep));
      this.outcome.set({
        kind: 'ok',
        expectedSha256: result.expectedSha256,
        actualSha256: result.actualSha256,
        deep: result.deep,
        plainOk: result.deep ? result.expectedSha256Plain === result.actualSha256Plain : null,
        verifiedAt: result.verifiedAt,
      });
    } catch (error) {
      this.outcome.set(verifyErrorOutcome(error));
    } finally {
      this.verifying.set(false);
    }
  }
}
