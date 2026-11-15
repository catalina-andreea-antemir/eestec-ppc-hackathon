// Types from the API contract (docs/api/openapi.yaml: LoginRequest, TokenPair, RefreshRequest, AccessToken, Role).

export type Role = 'DISPATCH' | 'FIELD' | 'CONNECTIONS' | 'METER_SERVICE' | 'ADMIN';

export interface LoginRequest {
  username: string;
  password: string;
}

/** POST /auth/login response (schema TokenPair). */
export interface TokenPair {
  /** JWT valid for 15 minutes; claims `sub` (user) and `role`. */
  accessToken: string;
  /** Stateless token, valid for 8 hours. */
  refreshToken: string;
  /** accessToken lifetime in seconds (900). */
  expiresIn: number;
}

/** POST /auth/refresh response (schema AccessToken): no new refreshToken. */
export interface AccessToken {
  accessToken: string;
  expiresIn: number;
}

export interface RefreshRequest {
  refreshToken: string;
}

/** What we read from the JWT payload for display and UI-side checks. */
export interface AuthUser {
  username: string;
  role: Role | null;
}
