import { HttpErrorResponse, HttpParams, HttpRequest, HttpResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

import { Role } from '../auth/auth.models';
import { OutageZones, PeakVsNormal, StorageAdvisor } from '../stats/stats.models';
import { describeStatsFailure, hasOutageData } from '../stats/stats-view';
import { handleStatsRoutes, MockStatsMode } from './mock-stats';

async function get<T>(
  path: string,
  role: Role,
  params: Record<string, string> = {},
  mode: MockStatsMode = 'normal',
): Promise<T> {
  const req = new HttpRequest('GET', `/api/v1${path}`, null, { params: new HttpParams({ fromObject: params }) });
  const res = await firstValueFrom(handleStatsRoutes(req, path, { sub: 'u', role }, mode)!);
  return (res as HttpResponse<T>).body!;
}

describe('mock /stats/*', () => {
  it('răspunde cu 403 pentru rolurile fără acces', async () => {
    for (const role of ['FIELD', 'CONNECTIONS', 'METER_SERVICE'] as Role[]) {
      const err = await get('/stats/requirements', role).catch((e: unknown) => e);
      expect(err).toBeInstanceOf(HttpErrorResponse);
      expect((err as HttpErrorResponse).status).toBe(403);
    }
  });

  it('matricea de storage are 12 celule, fără goluri', async () => {
    const advisor = await get<StorageAdvisor>('/stats/storage-advisor', 'DISPATCH');
    expect(advisor.matrix).toHaveLength(12);
    expect(new Set(advisor.matrix.map((c) => c.category + c.storage)).size).toBe(12);
    expect(advisor.recommendations.find((r) => r.category === 'INSPECTION_VIDEO')).toMatchObject({ currentPolicyStorage: 'FTP', recommendedStorage: 'S3' });
    expect(advisor.interpretation).toBeTruthy();
  });

  it('peak-vs-normal: storm e exemplul din contract, peak e altă zi', async () => {
    const storm = await get<PeakVsNormal>('/stats/peak-vs-normal', 'ADMIN');
    const outages = storm.categories.find((c) => c.category === 'OUTAGE_RECORD')!;
    expect(storm.peak.scenario).toBe('storm');
    expect(outages.peak.requests).toBe(480);
    expect(outages.p95ChangePercent).toBe(108.8);

    const peak = await get<PeakVsNormal>('/stats/peak-vs-normal', 'ADMIN', { peakScenario: 'peak' });
    expect(peak.categories.find((c) => c.category === 'METER_READING')!.peak.requests).toBe(10800);
    expect(peak.interpretation).not.toBe(storm.interpretation);
  });

  it('distribuția pe ore a avariilor dă exact totalurile din clasament', async () => {
    const zones = await get<OutageZones>('/stats/outage-zones', 'DISPATCH');
    for (const r of zones.ranking) {
      const sum = zones.byHour.filter((h) => h.zone === r.zone).reduce((s, h) => s + h.outages, 0);
      expect(sum).toBe(r.outages);
    }
    expect(zones.ranking.reduce((s, r) => s + r.outages, 0)).toBe(zones.totalOutages);
  });

  it('interval fără avarii: total 0 și câmpuri null (nu 0), ca în O7', async () => {
    const zones = await get<OutageZones>('/stats/outage-zones', 'DISPATCH', {}, 'no-outages');
    expect(zones.totalOutages).toBe(0);
    expect(zones.ranking).toHaveLength(3);
    for (const z of zones.ranking) {
      expect(z).toMatchObject({ outages: 0, sharePercent: null, vsAverageRatio: null, peakHour: null });
    }
    expect(hasOutageData(zones)).toBe(false);
  });

  it('o zonă fără avarii: 0 real la procent și raport, null doar la ora de vârf', async () => {
    const zones = await get<OutageZones>('/stats/outage-zones', 'DISPATCH', {}, 'zone-without-outages');
    expect(zones.ranking.find((z) => z.zone === 'Banat')).toMatchObject({
      outages: 0,
      sharePercent: 0,
      vsAverageRatio: 0,
      peakHour: null,
    });
    expect(hasOutageData(zones)).toBe(true);
  });

  it('lipsesc măsurătorile: cele două statistici de performanță dau 503 cu reason, restul merg', async () => {
    const reasons = {
      '/stats/peak-vs-normal': 'REQUEST_METRICS_UNAVAILABLE',
      '/stats/storage-advisor': 'STORAGE_MEASUREMENTS_INCOMPLETE',
    };
    for (const [path, reason] of Object.entries(reasons)) {
      const err = (await get(path, 'ADMIN', {}, 'metrics-missing').catch((e: unknown) => e)) as HttpErrorResponse;
      expect(err.status).toBe(503);
      expect(err.error).toMatchObject({ code: 'STORAGE_UNAVAILABLE', details: { reason } });
      expect(describeStatsFailure(err).kind).toBe('unavailable');
    }
    const zones = await get<OutageZones>('/stats/outage-zones', 'ADMIN', {}, 'metrics-missing');
    expect(zones.totalOutages).toBe(142);
  });
});
