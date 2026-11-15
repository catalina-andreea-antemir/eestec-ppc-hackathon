import { Role } from '../auth/auth.models';
import { FileMetadata, StorageType } from '../upload/upload.models';

/**
 * Etichetele în română pentru valorile tehnice din contractul API.
 * Valorile brute (FIELD, CONFIRMED, DB...) rămân în cod și în cereri; în interfață se afișează doar acestea.
 */
export const ROLE_LABELS: Record<Role, string> = {
  DISPATCH: 'Dispecerat',
  FIELD: 'Echipă teren',
  CONNECTIONS: 'Racordări',
  METER_SERVICE: 'Serviciu contoare',
  ADMIN: 'Administrator',
};

export const FILE_STATUS_LABELS: Record<FileMetadata['status'], string> = {
  PENDING: 'În așteptare',
  CONFIRMED: 'Confirmat',
  FAILED: 'Eșuat',
};

export const STORAGE_LABELS: Record<StorageType, string> = {
  FTP: 'FTP',
  S3: 'S3',
  DB: 'Bază de date',
};

export function roleLabel(role: Role | null | undefined): string {
  return role ? (ROLE_LABELS[role] ?? role) : '';
}
