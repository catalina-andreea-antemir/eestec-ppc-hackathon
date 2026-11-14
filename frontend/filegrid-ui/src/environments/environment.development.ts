// Configurația pentru `ng serve`.
// useMocks: true -> cererile către /api/v1 sunt servite de mock-api.interceptor.ts,
// ca interfața să meargă înainte ca backend-ul să existe. Când API-ul rulează
// (docker compose), pune false și cererile trec prin proxy.conf.json.
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
  useMocks: true,
};
