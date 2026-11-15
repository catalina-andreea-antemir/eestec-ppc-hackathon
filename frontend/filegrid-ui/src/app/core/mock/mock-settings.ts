/**
 * Upload mock settings, kept in localStorage so they survive a refresh.
 * Changed from the simulation panel on the Upload page (only visible with environment.useMocks).
 */
export interface MockSettings {
  /** Probability (0..1) that an upload request gets a transient error. */
  failureRate: number;
  /** Simulated network speed, in MB/s. */
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
    /* ignored */
  }
}
