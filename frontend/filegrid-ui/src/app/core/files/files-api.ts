import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { DownloadLink, FileMetadata, FilePage, SearchFilters, VerifyResult } from './files.models';
import { toApiParams } from './search-query';

/**
 * Apelurile HTTP pentru căutare, detalii, verificare și link de descărcare (contractul: /files/*).
 * Token-ul și reînnoirea lui le pune authInterceptor.
 *
 * Atenție: fișierul în sine NU se descarcă prin HttpClient (l-ar încărca tot în memorie);
 * vezi FileDownloader, care deschide link-ul semnat în browser.
 */
@Injectable({ providedIn: 'root' })
export class FilesApi {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** GET /files cu filtre și paginare. Fără categorie, serverul restrânge la categoriile rolului. */
  search(filters: SearchFilters): Observable<FilePage> {
    return this.http.get<FilePage>(`${this.base}/files`, { params: toApiParams(filters) });
  }

  /** GET /files/{id}. */
  get(id: string): Observable<FileMetadata> {
    return this.http.get<FileMetadata>(this.fileUrl(id));
  }

  /**
   * GET /files/{id}/verify: serverul recitește fișierul din storage și recalculează SHA-256.
   * 200 = OK; 409 cu code FILE_CORRUPT = hash nepotrivit (hash-urile sunt în details).
   * `deep` are efect doar la CONNECTION_DOC (decriptează și verifică și sha256Plain).
   */
  verify(id: string, deep = false): Observable<VerifyResult> {
    const params: Record<string, string> = deep ? { deep: 'true' } : {};
    return this.http.get<VerifyResult>(`${this.fileUrl(id)}/verify`, { params });
  }

  /** POST /files/{id}/download-link: URL semnat, valabil 15 minute. */
  createDownloadLink(id: string): Observable<DownloadLink> {
    return this.http.post<DownloadLink>(`${this.fileUrl(id)}/download-link`, null);
  }

  private fileUrl(id: string): string {
    return `${this.base}/files/${encodeURIComponent(id)}`;
  }
}
