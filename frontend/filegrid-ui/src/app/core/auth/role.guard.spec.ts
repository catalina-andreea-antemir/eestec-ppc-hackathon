import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';

import { STATS_ROLES } from '../stats/stats.models';
import { Role } from './auth.models';
import { roleGuard } from './role.guard';

function fakeJwt(payload: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part(payload)}.x`;
}

function runGuardAs(role: Role): boolean | UrlTree {
  sessionStorage.setItem('filegrid.accessToken', fakeJwt({ sub: 'u', role }));
  TestBed.configureTestingModule({
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
  });
  const guard = roleGuard(...STATS_ROLES);
  return runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
    guard({} as ActivatedRouteSnapshot, { url: '/stats' } as RouterStateSnapshot),
  ) as boolean | UrlTree;
}

describe('roleGuard pentru /stats', () => {
  beforeEach(() => sessionStorage.clear());

  it.each<Role>(['DISPATCH', 'ADMIN'])('lasă să treacă %s', (role) => {
    expect(runGuardAs(role)).toBe(true);
  });

  it.each<Role>(['FIELD', 'CONNECTIONS', 'METER_SERVICE'])('trimite %s la Acasă', (role) => {
    const result = runGuardAs(role);
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toBe('/');
  });
});
