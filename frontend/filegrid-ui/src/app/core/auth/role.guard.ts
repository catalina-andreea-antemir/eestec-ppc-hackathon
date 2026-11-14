import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Role } from './auth.models';
import { AuthService } from './auth.service';

/**
 * Lasă să treacă doar rolurile date (ADMIN trece mereu, ca în AuthService.hasRole).
 * Se pune după authGuard: cine nu e autentificat ajunge la /login, cine n-are rolul ajunge la Acasă.
 * Interfața doar ascunde pagina; regula adevărată o aplică serverul (403).
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    return auth.hasRole(...roles) ? true : inject(Router).createUrlTree(['/']);
  };
}
