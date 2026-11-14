// Tipurile din contractul API pentru căutare, verificare și descărcare
// (docs/api/openapi.yaml: FilePage, VerifyResult, DownloadLink) și matricea rolurilor 4.4.

import { Role } from '../auth/auth.models';
import { Category, FileMetadata } from '../upload/upload.models';

export type { Category, FileMetadata } from '../upload/upload.models';

/** Răspunsul de la GET /files (schema FilePage). */
export interface FilePage {
  items: FileMetadata[];
  /** De la 1. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Răspunsul 200 de la GET /files/{id}/verify (schema VerifyResult). La nepotrivire vine 409 FILE_CORRUPT. */
export interface VerifyResult {
  fileId: string;
  /** Mereu true la 200. */
  ok: boolean;
  algorithm: 'SHA-256';
  deep: boolean;
  expectedSha256: string;
  actualSha256: string;
  /** Doar cu deep=true la CONNECTION_DOC. */
  expectedSha256Plain?: string | null;
  actualSha256Plain?: string | null;
  verifiedAt: string;
}

/** Răspunsul de la POST /files/{id}/download-link (schema DownloadLink). */
export interface DownloadLink {
  /** Cale relativă `/api/v1/downloads/{token}`, valabilă 15 minute. */
  url: string;
  expiresAt: string;
}

/** Filtrele de căutare (parametrii lui GET /files). */
export interface SearchFilters {
  category: Category | null;
  /** Text în numele fișierului; nu se aplică la CONNECTION_DOC (nume criptat pe server). */
  q: string;
  /** ISO-8601 UTC, inclusiv (după createdAt). */
  from: string | null;
  /** ISO-8601 UTC, exclusiv (după createdAt). */
  to: string | null;
  page: number;
  size: number;
}

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;
export const PAGE_SIZES: readonly number[] = [10, 20, 50, 100];

export const EMPTY_FILTERS: SearchFilters = { category: null, q: '', from: null, to: null, page: 1, size: DEFAULT_PAGE_SIZE };

export const ALL_CATEGORIES: readonly Category[] = ['METER_READING', 'OUTAGE_RECORD', 'CONNECTION_DOC', 'INSPECTION_VIDEO'];

export const CATEGORY_LABELS: Record<Category, string> = {
  METER_READING: 'Citiri contoare',
  OUTAGE_RECORD: 'Avarii',
  CONNECTION_DOC: 'Documente de racordare',
  INSPECTION_VIDEO: 'Video-uri dronă',
};

/**
 * Cine poate căuta și descărca fiecare categorie (matricea 4.4, coloana „Poate căuta și descărca”).
 * ADMIN le poate pe toate (AuthService.hasRole îl lasă mereu); METER_SERVICE niciuna.
 */
export const SEARCH_ROLES: Record<Category, Role[]> = {
  METER_READING: ['DISPATCH'],
  OUTAGE_RECORD: ['FIELD', 'DISPATCH'],
  CONNECTION_DOC: ['CONNECTIONS'],
  INSPECTION_VIDEO: ['FIELD'],
};

/** Categoriile pe care un rol le poate căuta (aceeași regulă ca pe server). */
export function searchableCategories(role: Role | null | undefined): Category[] {
  if (!role) {
    return [];
  }
  return ALL_CATEGORIES.filter((c) => role === 'ADMIN' || SEARCH_ROLES[c].includes(role));
}
