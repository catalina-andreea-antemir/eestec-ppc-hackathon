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

// Dashboard numbers and dates in Romanian format (5.400; 0,6%), matching the interpretation sentences.
registerLocaleData(localeRo);

/**
 * Statistics page (F6), DISPATCH and ADMIN only: 5 decision cards, each with the server's interpretation
 * sentence on top and little data underneath. Each card fetches its own /stats/* endpoint, so an error on one
 * endpoint does not hide the others.
 */
@Component({
  selector: 'app-dashboard',
  imports: [Topbar, RequirementsCard, StorageAdvisorCard, PeakCard, OutageZonesCard, AnomaliesCard],
  // Times in UTC, matching the server's interpretation sentences ("at 03:15", "peak at 09:00").
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

  /** Mock only: which O7 situation we simulate (normal data, interval without outages, 503 without measurements). */
  protected readonly mockModes = MOCK_STATS_MODES;
  protected readonly mockMode = signal<MockStatsMode>(getMockStatsMode());

  protected changeMockMode(mode: string): void {
    setMockStatsMode(mode as MockStatsMode);
    this.mockMode.set(getMockStatsMode());
    this.reload.update((n) => n + 1);
  }
}
