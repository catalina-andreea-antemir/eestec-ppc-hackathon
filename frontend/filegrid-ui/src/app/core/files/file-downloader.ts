import { DOCUMENT } from '@angular/common';
import { inject, Injectable, InjectionToken } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FilesApi } from './files-api';
import { DownloadLink, FileMetadata } from './files.models';

/**
 * Pornește descărcarea nativă a browserului pentru un URL. Implicit: un `<a href download>` pe care
 * îl „apăsăm” din cod. În teste se înlocuiește, ca să vedem ce URL s-ar fi deschis.
 */
export type NativeDownloadFn = (url: string, fileName: string) => void;

export const NATIVE_DOWNLOAD = new InjectionToken<NativeDownloadFn>('NATIVE_DOWNLOAD', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    return (url, fileName) => {
      const link = document.createElement('a');
      link.href = url;
      // Pe backend-ul real numele vine din Content-Disposition; `download` contează la URL-urile blob: din mock.
      link.download = fileName;
      link.rel = 'noopener';
      link.style.display = 'none';
      document.body.appendChild(link);
      link.click();
      link.remove();
    };
  },
});

/** Link-ul se consideră expirat cu puțin înainte de expiresAt, ca browserul să apuce să-l deschidă. */
export const EXPIRY_MARGIN_MS = 30_000;

export function isLinkExpired(link: DownloadLink, now = Date.now()): boolean {
  const expiresAt = Date.parse(link.expiresAt);
  return Number.isNaN(expiresAt) || expiresAt - EXPIRY_MARGIN_MS <= now;
}

/**
 * Descărcarea unui fișier:
 * 1. POST /files/{id}/download-link (cu token; aici se verifică rolul și se auditează accesul);
 * 2. browserul deschide URL-ul semnat `/api/v1/downloads/{token}` și descarcă **nativ**: în streaming,
 *    direct pe disc, cu reluare (Range) din managerul de descărcări — fără fetch/HttpClient, deci fără
 *    să țină un video de 1 GB în memoria paginii. GET /downloads/{token} nu cere Authorization.
 *
 * Link-ul e valabil 15 minute; un link expirat primește 401 de la server. De aceea cerem un link nou
 * la fiecare apăsare și, dacă cel primit e deja (aproape) expirat — ceas decalat, pagină lăsată în
 * fundal —, mai cerem o dată.
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
