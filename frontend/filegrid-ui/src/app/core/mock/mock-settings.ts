/**
 * Setările mock-ului pentru upload, păstrate în localStorage ca să rămână după refresh.
 * Se schimbă din panoul „Mock” al paginii Upload (vizibil doar cu environment.useMocks).
 */
export interface MockSettings {
  /** Probabilitatea (0..1) ca o cerere de upload să primească o eroare trecătoare. */
  failureRate: number;
  /** Viteza simulată a rețelei, în MB/s. */
  speedMBps: number;
}

const KEY = 'filegrid.mock.settings';
export const DEFAULT_MOCK_SETTINGS: MockSettings = { failureRate: 0, speedMBps: 25 };

export function getMockSettings(): MockSettings {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...DEFAULT_MOCK_SETTINGS, ...(JSON.parse(raw) as Partial<MockSettings>) } : DEFAULT_MOCK_SETTINGS;
  } catch {
    return DEFAULT_MOCK_SETTINGS;
  }
}

export function setMockSettings(settings: MockSettings): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(settings));
  } catch {
    /* ignorat */
  }
}
