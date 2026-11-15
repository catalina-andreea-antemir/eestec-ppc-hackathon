import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Anomalies, AnomaliesQuery, OutageZones, PeakVsNormal, Requirements, StorageAdvisor } from './stats.models';

/**
 * HTTP calls for the dashboard (contract: /stats/*, allowed only for DISPATCH and ADMIN).
 * authInterceptor adds the token and handles its refresh.
 */
@Injectable({ providedIn: 'root' })
export class StatsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stats`;

  /** GET /stats/storage-advisor: the 4 x 3 score matrix and the recommendations. */
  storageAdvisor(): Observable<StorageAdvisor> {
    return this.http.get<StorageAdvisor>(`${this.base}/storage-advisor`);
  }

  /** GET /stats/peak-vs-normal: normal day vs. peak day, live data only. */
  peakVsNormal(peakScenario = 'storm', normalScenario = 'normal'): Observable<PeakVsNormal> {
    return this.http.get<PeakVsNormal>(`${this.base}/peak-vs-normal`, { params: { normalScenario, peakScenario } });
  }

  /** GET /stats/anomalies: abnormal consumption (paged), silent meters, readings per zone and hour. */
  anomalies(query: AnomaliesQuery = {}): Observable<Anomalies> {
    const params: Record<string, string> = {};
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) {
        params[key] = String(value);
      }
    }
    return this.http.get<Anomalies>(`${this.base}/anomalies`, { params });
  }

  /** GET /stats/outage-zones: zones ranked by number of outages. */
  outageZones(): Observable<OutageZones> {
    return this.http.get<OutageZones>(`${this.base}/outage-zones`);
  }

  /** GET /stats/requirements: the 4 business requirements (green/red). */
  requirements(): Observable<Requirements> {
    return this.http.get<Requirements>(`${this.base}/requirements`);
  }
}
