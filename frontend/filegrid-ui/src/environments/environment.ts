// Production configuration (the build served by nginx).
// Requests go to the real API on the same origin (https://localhost/api/v1).
export const environment = {
  production: true,
  apiBaseUrl: '/api/v1',
  useMocks: false,
};
