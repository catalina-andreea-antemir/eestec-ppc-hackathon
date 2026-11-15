import { DOCUMENT } from '@angular/common';
import { inject, Injectable, InjectionToken } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FilesApi } from './files-api';
import { DownloadLink, FileMetadata } from './files.models';

/**
 * Starts the browser's native download for a URL. By default: an `<a href download>` that we "click"
 * from code. Tests replace it to see which URL would have been opened.
 */
export type NativeDownloadFn = (url: string, fileName: string) => void;

export const NATIVE_DOWNLOAD = new InjectionToken<NativeDownloadFn>('NATIVE_DOWNLOAD', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    return (url, fileName) => {
      const link = document.createElement('a');
      link.href = url;
      // On the real backend the name comes from Content-Disposition; `download` matters for the mock's blob: URLs.
      link.download = fileName;
      link.rel = 'noopener';
      link.style.display = 'none';
      document.body.appendChild(link);
      link.click();
      link.remove();
    };
  },
});

/** The link counts as expired slightly before expiresAt, so the browser has time to open it. */
export const EXPIRY_MARGIN_MS = 30_000;

export function isLinkExpired(link: DownloadLink, now = Date.now()): boolean {
  const expiresAt = Date.parse(link.expiresAt);
  return Number.isNaN(expiresAt) || expiresAt - EXPIRY_MARGIN_MS <= now;
}

/**
 * Downloading a file:
 * 1. POST /files/{id}/download-link (with the token; this is where the role is checked and access is audited);
 * 2. the browser opens the signed URL `/api/v1/downloads/{token}` and downloads **natively**: streamed straight
 *    to disk, resumable (Range) from the download manager - no fetch/HttpClient, so a 1 GB video is never held
 *    in the page's memory. GET /downloads/{token} needs no Authorization.
 *
 * A link is valid for 15 minutes; an expired link gets a 401 from the server. That is why we request a fresh
 * link on every click and, if the one we get is already (almost) expired - skewed clock, page left in the
 * background - we ask once more.
 */
@Injectable({ providedIn: 'root' })
export class FileDownloader {
  private readonly api = inject(FilesApi);
  private readonly openNative = inject(NATIVE_DOWNLOAD);

  async download(file: Pick<FileMetadata, 'id' | 'originalName'>, now: () => number = Date.now): Promise<DownloadLink> {
    let link = await firstValueFrom(this.api.createDownloadLink(file.id));
    if (isLinkExpired(link, now())) {
      link = await firstValueFrom(this.api.createDownloadLink(file.id));
      if (isLinkExpired(link, now())) {
        throw new LinkExpiredError();
      }
    }
    this.openNative(link.url, file.originalName);
    return link;
  }
}

export class LinkExpiredError extends Error {
  constructor() {
    super('Link-ul de descărcare a expirat imediat după creare. Verifică ceasul calculatorului și încearcă din nou.');
    this.name = 'LinkExpiredError';
  }
}
