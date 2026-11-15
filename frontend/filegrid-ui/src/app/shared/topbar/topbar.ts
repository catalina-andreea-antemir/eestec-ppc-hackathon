import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { roleLabel } from '../../core/i18n/labels';
import { searchableCategories } from '../../core/files/files.models';
import { canViewStats } from '../../core/stats/stats.models';
import { CATEGORY_RULES, MANUAL_CATEGORIES } from '../../core/upload/upload.models';
import { Logo } from '../logo/logo';

/** Bara de sus, comună paginilor protejate: navigare, utilizator, ieșire. */
@Component({
  selector: 'app-topbar',
  imports: [RouterLink, RouterLinkActive, Logo],
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  protected readonly auth = inject(AuthService);
  protected readonly roleLabel = roleLabel;

  /** Link-ul Upload apare doar pentru rolurile care pot încărca ceva (matricea 4.4). */
  protected readonly canUpload = computed(() => {
    this.auth.user();
    return MANUAL_CATEGORIES.some((c) => this.auth.hasRole(...CATEGORY_RULES[c].roles));
  });

  /** Link-ul Căutare apare doar pentru rolurile care pot căuta ceva (METER_SERVICE nu poate). */
  protected readonly canSearch = computed(() => searchableCategories(this.auth.user()?.role).length > 0);

  /** Link-ul Statistici apare doar pentru DISPATCH și ADMIN (x-roles pe /stats/*). */
  protected readonly canViewStats = computed(() => canViewStats(this.auth.user()?.role));
}
