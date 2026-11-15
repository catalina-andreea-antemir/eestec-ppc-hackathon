import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { FilesApi } from '../../core/files/files-api';
import {
  Category,
  CATEGORY_LABELS,
  EMPTY_FILTERS,
  FileMetadata,
  searchableCategories,
} from '../../core/files/files.models';
import { toQueryParams } from '../../core/files/search-query';
import { roleLabel } from '../../core/i18n/labels';
import { canViewStats } from '../../core/stats/stats.models';
import { CATEGORY_RULES, formatBytes, MANUAL_CATEGORIES } from '../../core/upload/upload.models';
import { Logo } from '../../shared/logo/logo';
import { Topbar } from '../../shared/topbar/topbar';

interface CategoryCount {
  category: Category;
  label: string;
  /** null = nu s-a putut încărca numărul (serverul a răspuns cu eroare). */
  total: number | null;
}

/**
 * Pagina de start după login: salut, acțiunile permise rolului, căutare rapidă,
 * numărul de fișiere pe categorie și ultimele fișiere încărcate.
 */
@Component({
  selector: 'app-home',
  imports: [Topbar, RouterLink, DatePipe, Logo],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly files = inject(FilesApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = CATEGORY_LABELS;
  protected readonly formatBytes = formatBytes;

  protected readonly greeting = greetingFor(new Date().getHours());
  protected readonly role = computed(() => roleLabel(this.auth.user()?.role));

  protected readonly canUpload = computed(() => {
    this.auth.user();
    return MANUAL_CATEGORIES.some((c) => this.auth.hasRole(...CATEGORY_RULES[c].roles));
  });
  protected readonly categories = computed(() => searchableCategories(this.auth.user()?.role));
  protected readonly canSearch = computed(() => this.categories().length > 0);
  protected readonly canViewStats = computed(() => canViewStats(this.auth.user()?.role));

  protected readonly quickQuery = signal('');
  protected readonly counts = signal<CategoryCount[] | null>(null);
  protected readonly recent = signal<FileMetadata[] | null>(null);
  protected readonly recentError = signal(false);

  ngOnInit(): void {
    if (!this.canSearch()) {
      return;
    }
    this.files
      .search({ ...EMPTY_FILTERS, size: 5 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.recent.set(page.items),
        error: () => this.recentError.set(true),
      });

    const requests = this.categories().map((category) =>
      this.files.search({ ...EMPTY_FILTERS, category, size: 1 }).pipe(
        map((page): CategoryCount => ({ category, label: CATEGORY_LABELS[category], total: page.totalItems })),
        catchError(() => of<CategoryCount>({ category, label: CATEGORY_LABELS[category], total: null })),
      ),
    );
    forkJoin(requests)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((counts) => this.counts.set(counts));
  }

  protected quickSearch(): void {
    void this.router.navigate(['/search'], {
      queryParams: toQueryParams({ ...EMPTY_FILTERS, q: this.quickQuery() }),
    });
  }

  protected openCategory(category: Category): void {
    void this.router.navigate(['/search'], { queryParams: toQueryParams({ ...EMPTY_FILTERS, category }) });
  }

  protected openFile(file: FileMetadata): void {
    void this.router.navigate(['/search'], {
      queryParams: toQueryParams({ ...EMPTY_FILTERS, category: file.category, q: file.encrypted ? '' : file.originalName }),
    });
  }
}

function greetingFor(hour: number): string {
  if (hour < 5) return 'Noapte bună';
  if (hour < 12) return 'Bună dimineața';
  if (hour < 18) return 'Bună ziua';
  return 'Bună seara';
}
