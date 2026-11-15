import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { roleLabel } from '../../core/i18n/labels';
import { searchableCategories } from '../../core/files/files.models';
import { canViewStats } from '../../core/stats/stats.models';
import { CATEGORY_RULES, MANUAL_CATEGORIES } from '../../core/upload/upload.models';
import { Logo } from '../logo/logo';

/** Top bar shared by the protected pages: navigation, user, sign out. */
@Component({
  selector: 'app-topbar',
  imports: [RouterLink, RouterLinkActive, Logo],
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  protected readonly auth = inject(AuthService);
  protected readonly roleLabel = roleLabel;

  /** The upload link only shows for roles that can upload something (matrix 4.4). */
  protected readonly canUpload = computed(() => {
    this.auth.user();
    return MANUAL_CATEGORIES.some((c) => this.auth.hasRole(...CATEGORY_RULES[c].roles));
  });

  /** The search link only shows for roles that can search something (METER_SERVICE cannot). */
  protected readonly canSearch = computed(() => searchableCategories(this.auth.user()?.role).length > 0);

  /** The statistics link only shows for DISPATCH and ADMIN (x-roles on /stats/*). */
  protected readonly canViewStats = computed(() => canViewStats(this.auth.user()?.role));
}
