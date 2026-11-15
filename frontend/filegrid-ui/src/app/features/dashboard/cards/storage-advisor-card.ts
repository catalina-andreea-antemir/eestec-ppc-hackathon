import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, untracked } from '@angular/core';

import { STORAGE_LABELS } from '../../../core/i18n/labels';
import { StatsApi } from '../../../core/stats/stats-api';
import { StorageAdvisor } from '../../../core/stats/stats.models';
import { policyInterpretation, policyRows } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

/**
 * Cartea 1: unde se stochează fiecare categorie (storage-ul din politică) și cifrele măsurate în benchmark
 * pentru acel storage. Decizie pentru demo: fără recomandări, comparații sau sugestii de schimbare.
 */
@Component({
  selector: 'app-storage-advisor-card',
  imports: [StatCard, DecimalPipe, DatePipe],
  templateUrl: './storage-advisor-card.html',
  styleUrl: './storage-advisor-card.scss',
})
export class StorageAdvisorCard {
  /** Crește când utilizatorul apasă „Reîncarcă”. */
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<StorageAdvisor>();

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  protected readonly rows = computed(() => {
    const d = this.data();
    return d ? policyRows(d) : [];
  });
  /** Propoziție neutră din politică; interpretarea serverului (despre recomandare) nu se afișează. */
  protected readonly interpretation = computed(() => (this.data() ? policyInterpretation(this.rows(), STORAGE_LABELS) : null));
  protected readonly storageLabels = STORAGE_LABELS;

  constructor() {
    effect(() => {
      this.reload();
      untracked(() => loadInto(this.result, this.api.storageAdvisor(), this.destroyRef));
    });
  }

  protected errorOf(): string {
    const r = this.result();
    return 'message' in r ? r.message : '';
  }
}
