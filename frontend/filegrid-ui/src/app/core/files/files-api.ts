import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { DownloadLink, FileMetadata, FilePage, SearchFilters, VerifyResult } from './files.models';
import { toApiParams } from './search-query';

/**
 * HTTP calls for search, details, verification and download links (contract: /files/*).
 * authInterceptor adds the token and handles its refresh.
 *
 * Note: the file itself is NOT downloaded through HttpClient (that would load it entirely into memory);
 * see FileDownloader, which opens the signed link in the browser.
 */
@Injectable({ providedIn: 'root' })
export class FilesApi {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** GET /files with filters and paging. Without a category, the server restricts to the role's categories. */
  search(filters: SearchFilters): Observable<FilePage> {
    return this.http.get<FilePage>(`${this.base}/files`, { params: toApiParams(filters) });
  }

  /** GET /files/{id}. */
  get(id: string): Observable<FileMetadata> {
    return this.http.get<FileMetadata>(this.fileUrl(id));
  }

  /**
   * GET /files/{id}/verify: the server re-reads the file from storage and recomputes SHA-256.
   * 200 = OK; 409 with code FILE_CORRUPT = hash mismatch (hashes are in details).
   * `deep` only applies to CONNECTION_DOC (also decrypts and checks sha256Plain).
   */
  verify(id: string, deep = false): Observable<VerifyResult> {
    const params: Record<string, string> = deep ? { deep: 'true' } : {};
    return this.http.get<VerifyResult>(`${this.fileUrl(id)}/verify`, { params });
  }

  /** POST /files/{id}/download-link: signed URL, valid for 15 minutes. */
  createDownloadLink(id: string): Observable<DownloadLink> {
    return this.http.post<DownloadLink>(`${this.fileUrl(id)}/download-link`, null);
  }

  private fileUrl(id: string): string {
    return `${this.base}/files/${encodeURIComponent(id)}`;
  }
}
