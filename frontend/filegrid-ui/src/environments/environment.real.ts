// Configuration for `npm run start:real`: `ng serve` without mocks.
// Requests to /api go through proxy.conf.json to nginx (https://localhost), so the system must be
// started first with scripts/start.ps1 (or start.sh).
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
  useMocks: false,
};
