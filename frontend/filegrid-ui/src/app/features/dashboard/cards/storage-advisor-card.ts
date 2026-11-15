import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, untracked } from '@angular/core';

import { STORAGE_LABELS } from '../../../core/i18n/labels';
import { StatsApi } from '../../../core/stats/stats-api';
import { StorageAdvisor } from '../../../core/stats/stats.models';
import { buildAdvisorMatrix, costAssumptionLine, CostLine, scoreTint } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

/** Card 1: which storage fits each category (4 x 3 score matrix, from the benchmark). */
@Component({
  selector: 'app-storage-advisor-card',
  imports: [StatCard, DecimalPipe, DatePipe],
  templateUrl: './storage-advisor-card.html',
  styleUrl: './storage-advisor-card.scss',
})
export class StorageAdvisorCard {
  /** Changes when the user clicks the reload button. */
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<StorageAdvisor>();

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  protected readonly matrix = computed(() => {
    const d = this.data();
    return d ? buildAdvisorMatrix(d) : null;
  });
  protected readonly cost = computed(() => {
    const d = this.data();
    return d ? costAssumptionLine(d.costAssumption) : null;
  });
  protected readonly tint = scoreTint;
  protected readonly storageLabels = STORAGE_LABELS;

  constructor() {
    effect(() => {
      this.reload();
      untracked(() => loadInto(this.result, this.api.storageAdvisor(), this.destroyRef));
    });
  }

  /** "FTP = 1,0, S3 = 1,4, DB = 3,2". */
  protected costValues(c: CostLine): string {
    return c.values.map((v) => `${v.storage} = ${v.cost.toLocaleString('ro-RO', { minimumFractionDigits: 1 })}`).join(', ');
  }

  protected errorOf(): string {
    const r = this.result();
    return 'message' in r ? r.message : '';
  }
}
