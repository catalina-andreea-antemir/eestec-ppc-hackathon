import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, untracked } from '@angular/core';

import { StatsApi } from '../../../core/stats/stats-api';
import { OutageZones } from '../../../core/stats/stats.models';
import { formatHour, formatPeakHour, formatRatio, formatShare, hasOutageData, zoneBars } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

/** Card 4: zones with the most outages and their peak hours. */
@Component({
  selector: 'app-outage-zones-card',
  imports: [StatCard, DatePipe],
  templateUrl: './outage-zones-card.html',
  styleUrl: './outage-zones-card.scss',
})
export class OutageZonesCard {
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<OutageZones>();

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  /** false = interval without outages: percentages and peak hours are null, not 0. */
  protected readonly hasData = computed(() => {
    const d = this.data();
    return !!d && hasOutageData(d);
  });
  protected readonly bars = computed(() => {
    const d = this.data();
    return d && this.hasData() ? zoneBars(d.ranking, d.byHour) : [];
  });
  /** Hourly maximum shared by every zone, so the strip intensities are comparable. */
  protected readonly hourMax = computed(() => Math.max(1, ...this.bars().flatMap((b) => b.hours)));
  protected readonly hour = formatHour;
  protected readonly peakHour = formatPeakHour;
  protected readonly share = formatShare;
  protected readonly ratio = formatRatio;
  protected readonly hourTicks = [0, 6, 12, 18];

  constructor() {
    effect(() => {
      this.reload();
      untracked(() => loadInto(this.result, this.api.outageZones(), this.destroyRef));
    });
  }

  protected errorOf(): string {
    const r = this.result();
    return 'message' in r ? r.message : '';
  }
}
