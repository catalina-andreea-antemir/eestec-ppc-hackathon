import { Role } from '../auth/auth.models';
import { FileMetadata, StorageType } from '../upload/upload.models';

/**
 * Romanian UI labels for the technical values of the API contract.
 * Raw values (FIELD, CONFIRMED, DB...) stay in code and requests; only these labels are shown in the UI.
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
