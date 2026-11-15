import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FileDownloader } from '../../core/files/file-downloader';
import { describeFileError, VerifyOutcome, verifyErrorOutcome } from '../../core/files/file-errors';
import { FilesApi } from '../../core/files/files-api';
import { FileMetadata } from '../../core/files/files.models';

/**
 * Download and verify-integrity buttons for one file (F5).
 *
 * - Download: requests a signed link and lets the browser download it natively (see FileDownloader).
 * - Verify: the server re-reads the file from storage and recomputes SHA-256; 200 -> green, 409 FILE_CORRUPT
 *   -> red, with the expected and actual hashes. For connection documents a full check can also be requested
 *   (deep: decrypts and also compares the original's hash).
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
