// API contract types (docs/api/openapi.yaml: Category, StorageType, Zone, FileMetadata,
// OutageDetails, UploadInit, UploadSession, ChunkAck) and the upload rules from the plan (4.2, 4.4).

import { Role } from '../auth/auth.models';

export type Category = 'METER_READING' | 'OUTAGE_RECORD' | 'CONNECTION_DOC' | 'INSPECTION_VIDEO';

/** Categories uploaded from the UI. METER_READING only comes in through the simulator (POST /meter-readings). */
export type ManualCategory = Exclude<Category, 'METER_READING'>;

export type StorageType = 'FTP' | 'S3' | 'DB';

export type Zone = 'Muntenia' | 'Banat' | 'Dobrogea';
export const ZONES: readonly Zone[] = ['Muntenia', 'Banat', 'Dobrogea'];

/** OUTAGE_RECORD only. */
export interface OutageDetails {
  zone: Zone;
  stationId?: string | null;
  /** ISO-8601 UTC. */
  occurredAt: string;
}

/** POST /files and POST /uploads/{id}/complete response. */
export interface FileMetadata {
  id: string;
  category: Category;
  originalName: string;
  contentType: string;
  size: number;
  /** SHA-256 of the stored bytes (encrypted for CONNECTION_DOC). */
  sha256: string;
  /** SHA-256 of the original file; only for encrypted documents. Shown by the UI. */
  sha256Plain?: string | null;
  storage: StorageType;
  status: 'PENDING' | 'CONFIRMED' | 'FAILED';
  encrypted: boolean;
  createdAt: string;
  createdBy: string;
  outage?: OutageDetails;
}

/** POST /uploads body. */
export interface UploadInit {
  category: 'INSPECTION_VIDEO';
  fileName: string;
  totalSize: number;
}

export type UploadStatus = 'IN_PROGRESS' | 'COMPLETED' | 'ABORTED' | 'ABANDONED';

/** State of a chunked upload (POST /uploads, GET /uploads/{id}, POST /uploads/{id}/abort). */
export interface UploadSession {
  uploadId: string;
  category: Category;
  fileName: string;
  storage: StorageType;
  totalSize: number;
  /** From the policy: between 5 MiB and 64 MiB (16 MiB by default). The last chunk may be smaller. */
  chunkSize: number;
  totalChunks: number;
  /** Next expected chunk (1-based). */
  nextChunk: number;
  receivedBytes: number;
  status: UploadStatus;
  createdAt: string;
  lastActivityAt: string;
}

/** PUT /uploads/{id}/chunks/{n} response. */
export interface ChunkAck {
  n: number;
  size: number;
  sha256: string;
  nextChunk: number;
  /** true if the chunk had already been received (idempotent confirmation). */
  duplicate: boolean;
}

const MB = 1024 * 1024;

export interface CategoryRule {
  label: string;
  description: string;
  /** Roles that can upload (ADMIN can do everything; see AuthService.hasRole). */
  roles: Role[];
  /** Maximum size from the default policy (4.2). */
  maxBytes: number;
  /** true: chunked upload (POST /uploads); false: POST /files. */
  chunked: boolean;
  /** Hint for the file picker (not a restriction). */
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

/** Human-friendly sizes (1 MB = 1024 x 1024 bytes). */
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
