// Configurația de producție (build-ul servit de nginx).
// Cererile merg la API-ul real, prin aceeași origine (https://localhost/api/v1).
export const environment = {
  production: true,
  apiBaseUrl: '/api/v1',
  useMocks: false,
};
