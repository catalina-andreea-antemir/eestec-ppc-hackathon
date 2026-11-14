// Tipurile din contractul API (docs/api/openapi.yaml: LoginRequest, TokenPair, RefreshRequest, AccessToken, Role).

export type Role = 'DISPATCH' | 'FIELD' | 'CONNECTIONS' | 'METER_SERVICE' | 'ADMIN';

export interface LoginRequest {
  username: string;
  password: string;
}

/** Răspunsul de la POST /auth/login (schema TokenPair). */
export interface TokenPair {
  /** JWT, valabil 15 minute; claim-uri `sub` (utilizator) și `role`. */
  accessToken: string;
  /** Token stateless, valabil 8 ore. */
  refreshToken: string;
  /** Secunde de valabilitate ale accessToken (900). */
  expiresIn: number;
}

/** Răspunsul de la POST /auth/refresh (schema AccessToken): fără refreshToken nou. */
export interface AccessToken {
  accessToken: string;
  expiresIn: number;
}

export interface RefreshRequest {
  refreshToken: string;
}

/** Ce extragem din payload-ul JWT pentru afișare și pentru verificări în interfață. */
export interface AuthUser {
  username: string;
  role: Role | null;
}
