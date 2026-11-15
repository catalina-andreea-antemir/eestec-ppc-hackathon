// Configuration for `ng serve`.
// useMocks: true -> requests to /api/v1 are served by mock-api.interceptor.ts, so the UI works
// without a backend. When the API is running (docker compose), set it to false and requests go
// through proxy.conf.json.
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
  useMocks: true,
};
