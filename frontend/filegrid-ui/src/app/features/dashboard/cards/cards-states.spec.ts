import { HttpEvent, HttpRequest, HttpResponse } from '@angular/common/http';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';

import { handleStatsRoutes, MockStatsMode } from '../../../core/mock/mock-stats';
import { StatsApi } from '../../../core/stats/stats-api';
import { UNAVAILABLE_REASONS } from '../../../core/stats/stats-view';
import { OutageZonesCard } from './outage-zones-card';
import { PeakCard } from './peak-card';
import { RequirementsCard } from './requirements-card';
import { StorageAdvisorCard } from './storage-advisor-card';

/** Răspunsul mock-ului pentru o cale, transformat într-un Observable sincron (valoare sau eroare). */
async function fromMock(path: string, mode: MockStatsMode): Promise<Observable<unknown>> {
  const req = new HttpRequest('GET', `/api/v1${path}`, null);
  try {
    const res = (await firstValueFrom(handleStatsRoutes(req, path, { sub: 'u', role: 'DISPATCH' }, mode)!)) as HttpEvent<unknown>;
    return of((res as HttpResponse<unknown>).body);
  } catch (err) {
    return throwError(() => err);
  }
}

/** Randează cartea cu StatsApi alimentat din mock, în situația dată. */
async function render(card: Type<unknown>, mode: MockStatsMode): Promise<HTMLElement> {
  const api = {
    outageZones: await fromMock('/stats/outage-zones', mode),
    peakVsNormal: await fromMock('/stats/peak-vs-normal', mode),
    storageAdvisor: await fromMock('/stats/storage-advisor', mode),
    requirements: await fromMock('/stats/requirements', mode),
  };
  TestBed.configureTestingModule({
    providers: [
      {
        provide: StatsApi,
        useValue: {
          outageZones: () => api.outageZones,
          peakVsNormal: () => api.peakVsNormal,
          storageAdvisor: () => api.storageAdvisor,
          requirements: () => api.requirements,
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(card);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('cărțile dashboard-ului în situațiile din O7', () => {
  it('interval fără avarii: „Nu există date pentru intervalul ales”, fără 0%, NaN sau null', async () => {
    const el = await render(OutageZonesCard, 'no-outages');
    const text = el.textContent ?? '';
    expect(el.querySelector('.empty')?.textContent).toContain('Nu există date pentru intervalul ales');
    expect(el.querySelectorAll('.zone')).toHaveLength(0);
    expect(text).not.toMatch(/NaN|null|undefined|0%|×0/);
  });

  it('o zonă cu 0 avarii reale arată 0, iar ora de vârf lipsă arată „—”', async () => {
    const el = await render(OutageZonesCard, 'zone-without-outages');
    const banat = [...el.querySelectorAll('.zone')].find((z) => z.textContent?.includes('Banat'))!;
    const text = banat.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('0 avarii · 0% · ×0,00 față de medie');
    expect(text).toContain('Vârf: —');
    expect(text).not.toMatch(/NaN|null/);
    expect(el.querySelector('.empty')).toBeNull();
  });

  it.each([
    ['peak-vs-normal', PeakCard, 'REQUEST_METRICS_UNAVAILABLE'],
    ['storage-advisor', StorageAdvisorCard, 'STORAGE_MEASUREMENTS_INCOMPLETE'],
  ] as const)('%s: 503 fără măsurători → mesaj informativ de indisponibilitate, nu eroare', async (_name, card, reason) => {
    const el = await render(card, 'metrics-missing');
    const info = el.querySelector('.unavailable');
    expect(info?.textContent).toContain(UNAVAILABLE_REASONS[reason]);
    expect(info?.getAttribute('role')).toBe('status');
    expect(el.querySelector('.error')).toBeNull();
  });

  it('cerințe: plăcuța cu date insuficiente e gri, AT_RISK roșu, celelalte verzi', async () => {
    const el = await render(RequirementsCard, 'normal');
    const tile = (id: string) => [...el.querySelectorAll('.tile')].find((t) => t.textContent?.includes(`${id} ·`))!;
    expect(tile('R4').className).toContain('neutral');
    expect(tile('R4').textContent).toContain('Date insuficiente: 3 din minim 5 transferuri video');
    expect(tile('R4').querySelector('.status')?.textContent).toContain('Date insuficiente');
    expect(tile('R2').className).toContain('red');
    expect(tile('R1').className).toContain('green');
    expect(tile('R3').className).toContain('green');
    expect(el.querySelector('.interpretation')?.textContent).not.toContain('Toate cele 4');
  });

  it('pe aceeași simulare, cartea de avarii merge în continuare', async () => {
    const el = await render(OutageZonesCard, 'metrics-missing');
    expect(el.querySelectorAll('.zone')).toHaveLength(3);
    expect(el.querySelector('.unavailable, .error')).toBeNull();
  });
});
