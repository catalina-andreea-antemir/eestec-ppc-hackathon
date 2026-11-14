import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Anomalies, AnomaliesQuery, OutageZones, PeakVsNormal, Requirements, StorageAdvisor } from './stats.models';

/**
 * Apelurile HTTP pentru dashboard (contractul: /stats/*, permise doar pentru DISPATCH și ADMIN).
 * Token-ul și reînnoirea lui le pune authInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class StatsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stats`;

  /** GET /stats/storage-advisor: matricea 4 × 3 cu scor și recomandările. */
  storageAdvisor(): Observable<StorageAdvisor> {
    return this.http.get<StorageAdvisor>(`${this.base}/storage-advisor`);
  }

  /** GET /stats/peak-vs-normal: zi normală vs. zi de vârf, doar date live. */
  peakVsNormal(peakScenario = 'storm', normalScenario = 'normal'): Observable<PeakVsNormal> {
    return this.http.get<PeakVsNormal>(`${this.base}/peak-vs-normal`, { params: { normalScenario, peakScenario } });
  }

  /** GET /stats/anomalies: consumuri anormale (paginate), contoare tăcute, citiri pe zonă și oră. */
  anomalies(query: AnomaliesQuery = {}): Observable<Anomalies> {
    const params: Record<string, string> = {};
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) {
        params[key] = String(value);
      }
    }
    return this.http.get<Anomalies>(`${this.base}/anomalies`, { params });
  }

  /** GET /stats/outage-zones: clasamentul zonelor după numărul de avarii. */
  outageZones(): Observable<OutageZones> {
    return this.http.get<OutageZones>(`${this.base}/outage-zones`);
  }

  /** GET /stats/requirements: cele 4 cerințe de business (verde/roșu). */
  requirements(): Observable<Requirements> {
    return this.http.get<Requirements>(`${this.base}/requirements`);
  }
}
