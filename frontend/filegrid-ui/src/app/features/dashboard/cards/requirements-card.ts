import { Component, computed, DestroyRef, effect, inject, input, untracked } from '@angular/core';

import { CATEGORY_LABELS } from '../../../core/files/files.models';
import { StatsApi } from '../../../core/stats/stats-api';
import { Requirements } from '../../../core/stats/stats.models';
import { requirementsInterpretation, requirementTone, sortRequirements, TONE_LABELS } from '../../../core/stats/stats-view';
import { loadable, loadInto, StatCard } from '../stat-card';

/**
 * Cartea 5: cele 4 cerințe de business ca plăcuțe verzi (îndeplinită), roșii (în pericol) sau gri
 * (date insuficiente: `current.sufficientData === false`).
 */
@Component({
  selector: 'app-requirements-card',
  imports: [StatCard],
  templateUrl: './requirements-card.html',
  styleUrl: './requirements-card.scss',
})
export class RequirementsCard {
  readonly reload = input(0);

  private readonly api = inject(StatsApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly result = loadable<Requirements>();
  protected readonly labels = CATEGORY_LABELS;
  protected readonly tone = requirementTone;
  protected readonly toneLabels = TONE_LABELS;

  protected readonly data = computed(() => {
    const r = this.result();
    return r.state === 'ok' ? r.data : null;
  });
  protected readonly tiles = computed(() => sortRequirements(this.data()?.requirements ?? []));
  protected readonly interpretation = computed(() => {
    const d = this.data();
    return d ? requirementsInterpretation(d) : null;
  });

  constructor() {
    effect(() => {
      this.reload();
      untracked(() => loadInto(this.result, this.api.requirements(), this.destroyRef));
    });
  }

  protected errorOf(): string {
    const r = this.result();
    return 'message' in r ? r.message : '';
  }
}
