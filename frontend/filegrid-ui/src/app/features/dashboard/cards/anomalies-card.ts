import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';

import { StatsApi } from '../../../core/stats/stats-api';
import { Anomalies } from '../../../core/stats/stats.models';
import { formatMinutes, sortAnomalies, sortSilentMeters } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

const PAGE_SIZE = 10;

/** Cartea 3: consumuri anormale și contoare care nu mai trimit nimic. */
@Component({
  selector: 'app-anomalies-card',
  imports: [StatCard, DecimalPipe, DatePipe],
  templateUrl: './anomalies-card.html',
  styleUrl: './anomalies-card.scss',
})
export class AnomaliesCard {
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<Anomalies>();
  protected readonly page = signal(1);

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  protected readonly anomalies = computed(() => sortAnomalies(this.data()?.anomalies.items ?? []));
  protected readonly silent = computed(() => sortSilentMeters(this.data()?.silentMeters ?? []));
  protected readonly minutes = formatMinutes;

  constructor() {
    effect(() => {
      this.reload();
      const page = this.page();
      untracked(() => loadInto(this.result, this.api.anomalies({ page, size: PAGE_SIZE }), this.destroyRef));
    });
  }

  protected errorOf(): string {
    const r = this.result();
    return r.state === 'error' ? r.message : '';
  }
}
