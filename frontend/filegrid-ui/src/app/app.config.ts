import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { mockApiInterceptor } from './core/mock/mock-api.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Ordinea contează: authInterceptor pune token-ul, apoi mock-ul (doar dacă
    // environment.useMocks e true) răspunde în locul backend-ului.
    provideHttpClient(withInterceptors([authInterceptor, mockApiInterceptor])),
  ],
};
