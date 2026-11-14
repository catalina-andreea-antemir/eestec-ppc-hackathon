// Tipurile din contractul API (docs/api/openapi.yaml: Category, StorageType, Zone, FileMetadata,
// OutageDetails, UploadInit, UploadSession, ChunkAck) și regulile de upload din plan (4.2, 4.4).

import { Role } from '../auth/auth.models';

export type Category = 'METER_READING' | 'OUTAGE_RECORD' | 'CONNECTION_DOC' | 'INSPECTION_VIDEO';

/** Categoriile încărcate din interfață. METER_READING intră doar prin simulator (POST /meter-readings). */
export type ManualCategory = Exclude<Category, 'METER_READING'>;

export type StorageType = 'FTP' | 'S3' | 'DB';

export type Zone = 'Muntenia' | 'Banat' | 'Dobrogea';
export const ZONES: readonly Zone[] = ['Muntenia', 'Banat', 'Dobrogea'];

/** Doar la OUTAGE_RECORD. */
export interface OutageDetails {
  zone: Zone;
  stationId?: string | null;
  /** ISO-8601 UTC. */
  occurredAt: string;
}

/** Răspunsul de la POST /files și POST /uploads/{id}/complete. */
export interface FileMetadata {
  id: string;
  category: Category;
  originalName: string;
  contentType: string;
  size: number;
  /** SHA-256 pe bytes-ii stocați (cifrați la CONNECTION_DOC). */
  sha256: string;
  /** SHA-256 pe fișierul original; doar la documente criptate. Îl arată interfața. */
  sha256Plain?: string | null;
  storage: StorageType;
  status: 'PENDING' | 'CONFIRMED' | 'FAILED';
  encrypted: boolean;
  createdAt: string;
  createdBy: string;
  outage?: OutageDetails;
}

/** Corpul de la POST /uploads. */
export interface UploadInit {
  category: 'INSPECTION_VIDEO';
  fileName: string;
  totalSize: number;
}

export type UploadStatus = 'IN_PROGRESS' | 'COMPLETED' | 'ABORTED' | 'ABANDONED';

/** Starea unui upload în bucăți (POST /uploads, GET /uploads/{id}, POST /uploads/{id}/abort). */
export interface UploadSession {
  uploadId: string;
  category: Category;
  fileName: string;
  storage: StorageType;
  totalSize: number;
  /** Din politică: între 5 MiB și 64 MiB (implicit 16 MiB). Ultima bucată poate fi mai mică. */
  chunkSize: number;
  totalChunks: number;
  /** Următoarea bucată așteptată (de la 1). */
  nextChunk: number;
  receivedBytes: number;
  status: UploadStatus;
  createdAt: string;
  lastActivityAt: string;
}

/** Răspunsul de la PUT /uploads/{id}/chunks/{n}. */
export interface ChunkAck {
  n: number;
  size: number;
  sha256: string;
  nextChunk: number;
  /** true dacă bucata fusese deja primită (confirmare idempotentă). */
  duplicate: boolean;
}

const MB = 1024 * 1024;

export interface CategoryRule {
  label: string;
  description: string;
  /** Rolurile care pot încărca (ADMIN poate tot; vezi AuthService.hasRole). */
  roles: Role[];
  /** Mărimea maximă din politica implicită (4.2). */
  maxBytes: number;
  /** true: upload în bucăți (POST /uploads); false: POST /files. */
  chunked: boolean;
  /** Sugestie pentru selectorul de fișiere (nu e o restricție). */
  accept: string;
}

export const CATEGORY_RULES: Record<ManualCategory, CategoryRule> = {
  OUTAGE_RECORD: {
    label: 'Avarie',
    description: 'Înregistrare de avarie (până la 20 MB), cu zonă, moment și stație.',
    roles: ['FIELD'],
    maxBytes: 20 * MB,
    chunked: false,
    accept: '.csv,.json,.txt,.log,.pdf,.jpg,.jpeg,.png',
  },
  CONNECTION_DOC: {
    label: 'Document de racordare',
    description: 'Document cu date personale (până la 20 MB), criptat înainte de stocare.',
    roles: ['CONNECTIONS'],
    maxBytes: 20 * MB,
    chunked: false,
    accept: '.pdf,.jpg,.jpeg,.png',
  },
  INSPECTION_VIDEO: {
    label: 'Video dronă',
    description: 'Video de inspecție (până la 5 GB), încărcat în bucăți, cu reluare.',
    roles: ['FIELD'],
    maxBytes: 5 * 1024 * MB,
    chunked: true,
    accept: 'video/*,.mp4,.mov,.mkv',
  },
};

export const MANUAL_CATEGORIES: readonly ManualCategory[] = ['OUTAGE_RECORD', 'CONNECTION_DOC', 'INSPECTION_VIDEO'];

/** Formatare prietenoasă pentru mărimi (1 MB = 1024 × 1024 octeți). */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 2 : 1).replace('.', ',')} ${units[unit]}`;
}
