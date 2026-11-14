import { DATE_PIPE_DEFAULT_OPTIONS, registerLocaleData } from '@angular/common';
import localeRo from '@angular/common/locales/ro';
import { Component, LOCALE_ID, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { getMockStatsMode, MOCK_STATS_MODES, MockStatsMode, setMockStatsMode } from '../../core/mock/mock-stats';
import { Topbar } from '../../shared/topbar/topbar';
import { AnomaliesCard } from './cards/anomalies-card';
import { OutageZonesCard } from './cards/outage-zones-card';
import { PeakCard } from './cards/peak-card';
import { RequirementsCard } from './cards/requirements-card';
import { StorageAdvisorCard } from './cards/storage-advisor-card';

// Numerele și datele din dashboard în format românesc (5.400; 0,6%), ca în propozițiile de interpretare.
registerLocaleData(localeRo);

/**
 * Pagina Statistici (F6), doar pentru DISPATCH și ADMIN: 5 cărți de decizie, fiecare cu propoziția de
 * interpretare de la server sus și puține date dedesubt. Fiecare carte își face singură cererea la /stats/*,
 * ca o eroare pe un endpoint să nu le ascundă pe celelalte.
 */
@Component({
  selector: 'app-dashboard',
  imports: [Topbar, RequirementsCard, StorageAdvisorCard, PeakCard, OutageZonesCard, AnomaliesCard],
  // Orele în UTC, ca în propozițiile de interpretare ale serverului („la 03:15”, „vârf la 09:00”).
  providers: [
    { provide: LOCALE_ID, useValue: 'ro' },
    { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { timezone: 'UTC' } },
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  protected readonly reload = signal(0);
  protected readonly useMocks = environment.useMocks;

  /** Doar pe mock: ce situație O7 simulăm (date normale, interval fără avarii, 503 fără măsurători). */
  protected readonly mockModes = MOCK_STATS_MODES;
  protected readonly mockMode = signal<MockStatsMode>(getMockStatsMode());

  protected changeMockMode(mode: string): void {
    setMockStatsMode(mode as MockStatsMode);
    this.mockMode.set(getMockStatsMode());
    this.reload.update((n) => n + 1);
  }
}
