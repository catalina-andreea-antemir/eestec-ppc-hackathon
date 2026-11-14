import { Component, DestroyRef, input, signal, WritableSignal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';

import { describeStatsError } from '../../core/stats/stats-view';

/** Starea unei cărți: se încarcă, are date sau a primit o eroare (fiecare carte separat). */
export type Loadable<T> = { state: 'loading' } | { state: 'ok'; data: T } | { state: 'error'; message: string };

/**
 * Pornește cererea și pune rezultatul în semnal. O carte care dă eroare (de exemplu 501 pe un endpoint
 * încă neimplementat) nu le strică pe celelalte.
 */
export function loadInto<T>(target: WritableSignal<Loadable<T>>, source: Observable<T>, destroyRef: DestroyRef): void {
  target.set({ state: 'loading' });
  source.pipe(takeUntilDestroyed(destroyRef)).subscribe({
    next: (data) => target.set({ state: 'ok', data }),
    error: (err: unknown) => target.set({ state: 'error', message: describeStatsError(err) }),
  });
}

export function loadable<T>(): WritableSignal<Loadable<T>> {
  return signal<Loadable<T>>({ state: 'loading' });
}

/**
 * Rama comună a celor 5 cărți: titlul, întrebarea la care răspunde cartea și, sus și vizibil,
 * propoziția de interpretare de la server. Datele (tabel, bare) vin dedesubt, prin conținut proiectat.
 */
@Component({
  selector: 'app-stat-card',
  template: `
    <section class="stat-card" [attr.aria-busy]="state() === 'loading'">
      <header>
        <h2>{{ heading() }}</h2>
        <p class="question">{{ question() }}</p>
      </header>
      @switch (state()) {
        @case ('loading') {
          <p class="placeholder">Se încarcă…</p>
        }
        @case ('error') {
          <p class="error" role="alert">{{ error() }}</p>
        }
        @default {
          @if (interpretation(); as text) {
            <p class="interpretation"><strong>Interpretare:</strong> {{ text }}</p>
          }
          <ng-content />
        }
      }
    </section>
  `,
  styles: `
    .stat-card {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 20px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      min-width: 0;
    }
    header {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    h2 {
      margin: 0;
      font-size: 1.15rem;
    }
    .question {
      margin: 0;
      color: var(--muted);
      font-size: 0.9rem;
    }
    .interpretation {
      margin: 0;
      padding: 12px 14px;
      border-left: 4px solid var(--accent);
      border-radius: 0 8px 8px 0;
      background: var(--accent-soft);
      font-size: 1rem;
    }
    .placeholder {
      margin: 0;
      color: var(--muted);
    }
    .error {
      margin: 0;
      color: var(--danger);
    }
  `,
})
export class StatCard {
  readonly heading = input.required<string>();
  readonly question = input.required<string>();
  readonly state = input.required<Loadable<unknown>['state']>();
  readonly error = input<string>('');
  readonly interpretation = input<string | null>(null);
}
