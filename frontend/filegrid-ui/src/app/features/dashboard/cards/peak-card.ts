import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { StatsApi } from '../../../core/stats/stats-api';
import { PEAK_SCENARIOS, PeakVsNormal } from '../../../core/stats/stats.models';
import { comparisonRows } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

/** Cartea 2: cum s-a comportat sistemul într-o zi de vârf față de una normală (doar trafic live). */
@Component({
  selector: 'app-peak-card',
  imports: [StatCard, DecimalPipe, DatePipe, FormsModule],
  templateUrl: './peak-card.html',
  styleUrl: './peak-card.scss',
})
export class PeakCard {
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<PeakVsNormal>();
  protected readonly scenarios = PEAK_SCENARIOS;
  protected readonly peakScenario = signal('storm');

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  protected readonly rows = computed(() => comparisonRows(this.data()?.categories ?? []));

  constructor() {
    effect(() => {
      this.reload();
      const scenario = this.peakScenario();
      untracked(() => loadInto(this.result, this.api.peakVsNormal(scenario), this.destroyRef));
    });
  }

  protected errorOf(): string {
    const r = this.result();
    return 'message' in r ? r.message : '';
  }
}
