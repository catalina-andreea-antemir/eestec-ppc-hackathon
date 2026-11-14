// Tipurile din contractul API (docs/api/openapi.yaml, secțiunea „Autentificare”).

export type Role = 'DISPATCH' | 'FIELD' | 'CONNECTIONS' | 'METER_SERVICE' | 'ADMIN';

export interface LoginRequest {
  username: string;
  password: string;
}

/** Răspunsul de la POST /auth/login și POST /auth/refresh. */
export interface TokenResponse {
  accessToken: string;
  /** Lipsește la /auth/refresh dacă serverul nu emite un refresh token nou. */
  refreshToken?: string;
  /** Durata de viață a accessToken-ului, în secunde (900 = 15 minute). */
  expiresIn: number;
}

export interface RefreshRequest {
  refreshToken: string;
}

/** Ce extragem din payload-ul JWT pentru afișare și pentru verificări în interfață. */
export interface AuthUser {
  username: string;
  roles: Role[];
}
