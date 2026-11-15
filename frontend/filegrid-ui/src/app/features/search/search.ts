import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { describeFileError } from '../../core/files/file-errors';
import { FilesApi } from '../../core/files/files-api';
import {
  Category,
  CATEGORY_LABELS,
  EMPTY_FILTERS,
  FileMetadata,
  FilePage,
  PAGE_SIZES,
  searchableCategories,
  SearchFilters,
} from '../../core/files/files.models';
import {
  dayEndExclusiveIso,
  dayStartIso,
  fromQueryParams,
  isoToDay,
  isoToInclusiveDay,
  rangeLabel,
  toQueryParams,
} from '../../core/files/search-query';
import { FILE_STATUS_LABELS, STORAGE_LABELS } from '../../core/i18n/labels';
import { formatBytes } from '../../core/upload/upload.models';
import { Topbar } from '../../shared/topbar/topbar';
import { FileActions } from '../download/file-actions';

/**
 * Pagina Căutare (F5): filtre (categorie, text, interval de date), tabel paginat, detalii pe rând
 * și, pentru fiecare fișier, „Descarcă” și „Verifică integritatea”.
 *
 * Filtrele stau în URL (/search?category=OUTAGE_RECORD&page=2), deci refresh-ul și butonul Înapoi
 * păstrează căutarea. Filtrul de categorie arată doar categoriile permise rolului (matricea 4.4).
 */
@Component({
  selector: 'app-search',
  imports: [Topbar, DatePipe, FileActions],
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class Search implements OnInit {
  private readonly api = inject(FilesApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = CATEGORY_LABELS;
  protected readonly statusLabels = FILE_STATUS_LABELS;
  protected readonly storageLabels = STORAGE_LABELS;
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly formatBytes = formatBytes;
  protected readonly rangeLabel = rangeLabel;

  /** Categoriile pe care rolul le poate căuta; goală la METER_SERVICE. */
  protected readonly categories = computed(() => searchableCategories(this.auth.user()?.role));

  /** Filtrele aplicate (din URL). */
  protected readonly filters = signal<SearchFilters>(EMPTY_FILTERS);

  // Câmpurile formularului (se aplică la „Caută”).
  protected readonly category = signal<Category | ''>('');
  protected readonly q = signal('');
  protected readonly fromDay = signal('');
  protected readonly toDay = signal('');
  protected readonly size = signal(EMPTY_FILTERS.size);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<FilePage | null>(null);
  protected readonly expandedId = signal<string | null>(null);

  /** La documente de racordare textul nu se aplică (numele e criptat pe server). */
  protected readonly textDisabled = computed(() => this.category() === 'CONNECTION_DOC');
  protected readonly dateError = computed(() =>
    this.fromDay() && this.toDay() && this.fromDay() > this.toDay() ? '„De la” trebuie să fie înainte de „Până la”.' : null,
  );

  private request?: Subscription;

  ngOnInit(): void {
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const filters = fromQueryParams(params);
      this.filters.set(filters);
      this.category.set(filters.category ?? '');
      this.q.set(filters.q);
      this.fromDay.set(isoToDay(filters.from));
      this.toDay.set(isoToInclusiveDay(filters.to));
      this.size.set(filters.size);
      this.load(filters);
    });
  }

  /** „Caută”: filtrele noi în URL, de la pagina 1 (abonarea de mai sus face cererea). */
  submit(): void {
    if (this.dateError()) {
      return;
    }
    this.navigate({
      category: this.category() || null,
      q: this.q(),
      from: this.fromDay() ? dayStartIso(this.fromDay()) : null,
      to: this.toDay() ? dayEndExclusiveIso(this.toDay()) : null,
      page: 1,
      size: this.size(),
    });
  }

  reset(): void {
    this.navigate({ ...EMPTY_FILTERS });
  }

  goToPage(page: number): void {
    const total = this.result()?.totalPages ?? 1;
    if (page >= 1 && page <= total) {
      this.navigate({ ...this.filters(), page });
    }
  }

  changeSize(size: number): void {
    this.size.set(size);
    this.navigate({ ...this.filters(), size, page: 1 });
  }

  toggle(file: FileMetadata): void {
    this.expandedId.update((id) => (id === file.id ? null : file.id));
  }

  retry(): void {
    this.load(this.filters());
  }

  private navigate(filters: SearchFilters): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: toQueryParams(filters) });
  }

  private load(filters: SearchFilters): void {
    if (this.categories().length === 0) {
      return;
    }
    this.request?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.request = this.api.search(filters).subscribe({
      next: (page) => {
        this.result.set(page);
        this.loading.set(false);
        // Pagina cerută nu mai există (au rămas mai puține rezultate): mergem la ultima.
        if (page.items.length === 0 && page.totalPages > 0 && filters.page > page.totalPages) {
          this.goToPage(page.totalPages);
        }
      },
      error: (error: unknown) => {
        this.result.set(null);
        this.error.set(describeFileError(error));
        this.loading.set(false);
      },
    });
  }
}
