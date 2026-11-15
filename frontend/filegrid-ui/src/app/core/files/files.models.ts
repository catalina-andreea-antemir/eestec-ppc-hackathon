// API contract types for search, verification and download
// (docs/api/openapi.yaml: FilePage, VerifyResult, DownloadLink) and role matrix 4.4.

import { Role } from '../auth/auth.models';
import { Category, FileMetadata } from '../upload/upload.models';

export type { Category, FileMetadata } from '../upload/upload.models';

/** GET /files response (schema FilePage). */
export interface FilePage {
  items: FileMetadata[];
  /** 1-based. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** GET /files/{id}/verify 200 response (schema VerifyResult). A mismatch returns 409 FILE_CORRUPT instead. */
export interface VerifyResult {
  fileId: string;
  /** Always true on 200. */
  ok: boolean;
  algorithm: 'SHA-256';
  deep: boolean;
  expectedSha256: string;
  actualSha256: string;
  /** Only with deep=true on CONNECTION_DOC. */
  expectedSha256Plain?: string | null;
  actualSha256Plain?: string | null;
  verifiedAt: string;
}

/** POST /files/{id}/download-link response (schema DownloadLink). */
export interface DownloadLink {
  /** Relative path `/api/v1/downloads/{token}`, valid for 15 minutes. */
  url: string;
  expiresAt: string;
}

/** Search filters (GET /files parameters). */
export interface SearchFilters {
  category: Category | null;
  /** Text in the file name; does not apply to CONNECTION_DOC (name encrypted on the server). */
  q: string;
  /** ISO-8601 UTC, inclusive (on createdAt). */
  from: string | null;
  /** ISO-8601 UTC, exclusive (on createdAt). */
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
 * Who can search and download each category (matrix 4.4, column "Can search and download").
 * ADMIN can do all of them (AuthService.hasRole always lets it through); METER_SERVICE none.
 */
export const SEARCH_ROLES: Record<Category, Role[]> = {
  METER_READING: ['DISPATCH'],
  OUTAGE_RECORD: ['FIELD', 'DISPATCH'],
  CONNECTION_DOC: ['CONNECTIONS'],
  INSPECTION_VIDEO: ['FIELD'],
};

/** Categories a role may search (same rule as the server). */
export function searchableCategories(role: Role | null | undefined): Category[] {
  if (!role) {
    return [];
  }
  return ALL_CATEGORIES.filter((c) => role === 'ADMIN' || SEARCH_ROLES[c].includes(role));
}
