import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { CATEGORY_RULES, MANUAL_CATEGORIES } from '../../core/upload/upload.models';

/** Bara de sus, comună paginilor protejate: navigare, utilizator, ieșire. */
@Component({
  selector: 'app-topbar',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  protected readonly auth = inject(AuthService);

  /** Link-ul Upload apare doar pentru rolurile care pot încărca ceva (matricea 4.4). */
  protected readonly canUpload = computed(() => {
    this.auth.user();
    return MANUAL_CATEGORIES.some((c) => this.auth.hasRole(...CATEGORY_RULES[c].roles));
  });
}
