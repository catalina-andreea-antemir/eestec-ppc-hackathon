import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Role } from './auth.models';
import { AuthService } from './auth.service';

/**
 * Lets only the given roles through (ADMIN always passes, as in AuthService.hasRole).
 * Goes after authGuard: unauthenticated users end up at /login, users without the role at Home.
 * The UI only hides the page; the real rule is enforced by the server (403).
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    return auth.hasRole(...roles) ? true : inject(Router).createUrlTree(['/']);
  };
}
