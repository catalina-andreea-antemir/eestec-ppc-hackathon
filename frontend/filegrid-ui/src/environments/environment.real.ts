// Configurația pentru `npm run start:real`: `ng serve` fără mock-uri.
// Cererile către /api trec prin proxy.conf.json la nginx (https://localhost), deci
// sistemul trebuie pornit înainte cu scripts/start.ps1 (sau start.sh).
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
  useMocks: false,
};
