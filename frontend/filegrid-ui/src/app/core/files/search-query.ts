import { Params } from '@angular/router';

import { ALL_CATEGORIES, Category, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, SearchFilters } from './files.models';

/**
 * Funcții pure pentru căutare: filtre ⇄ parametrii lui GET /files ⇄ parametrii din URL-ul paginii.
 * Sunt separate de componentă ca să se poată testa ușor.
 */

/** Parametrii pentru GET /files; lipsesc cei goi, ca serverul să aplice valorile implicite. */
export function toApiParams(filters: SearchFilters): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.category) {
    params['category'] = filters.category;
  }
  const q = filters.q.trim();
  // La documente de racordare numele e criptat pe server, deci q nu are efect; nu-l mai trimitem.
  if (q && filters.category !== 'CONNECTION_DOC') {
    params['q'] = q.slice(0, 200);
  }
  if (filters.from) {
    params['from'] = filters.from;
  }
  if (filters.to) {
    params['to'] = filters.to;
  }
  params['page'] = String(clampPage(filters.page));
  params['size'] = String(clampSize(filters.size));
  return params;
}

/**
 * Ziua aleasă în `<input type="date">` (YYYY-MM-DD, ora României a browserului) → momentul
 * de început al zilei, în UTC. `from` e inclusiv.
 */
export function dayStartIso(day: string): string | null {
  const date = parseLocalDay(day);
  return date ? toIsoSeconds(date) : null;
}

/**
 * Ziua „până la” e inclusivă în interfață, dar `to` e exclusiv în contract:
 * trimitem începutul zilei următoare.
 */
export function dayEndExclusiveIso(day: string): string | null {
  const date = parseLocalDay(day);
  if (!date) {
    return null;
  }
  date.setDate(date.getDate() + 1);
  return toIsoSeconds(date);
}

/** Inversul lui dayStartIso: momentul UTC → ziua locală, pentru câmpul de dată. */
export function isoToDay(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Inversul lui dayEndExclusiveIso. */
export function isoToInclusiveDay(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  date.setDate(date.getDate() - 1);
  return isoToDay(date.toISOString());
}

/** Filtrele în URL-ul paginii (/search?category=...&page=2), ca refresh-ul și link-urile să le păstreze. */
export function toQueryParams(filters: SearchFilters): Params {
  return {
    category: filters.category ?? null,
    q: filters.q.trim() || null,
    from: filters.from,
    to: filters.to,
    page: filters.page > 1 ? filters.page : null,
    size: filters.size !== DEFAULT_PAGE_SIZE ? filters.size : null,
  };
}

/** Citește filtrele din URL; valorile greșite sunt ignorate (se folosesc cele implicite). */
export function fromQueryParams(params: Params): SearchFilters {
  const category = ALL_CATEGORIES.includes(params['category']) ? (params['category'] as Category) : null;
  return {
    category,
    q: typeof params['q'] === 'string' ? params['q'] : '',
    from: validIso(params['from']),
    to: validIso(params['to']),
    page: clampPage(Number(params['page'] ?? 1)),
    size: clampSize(Number(params['size'] ?? DEFAULT_PAGE_SIZE)),
  };
}

export function clampPage(page: number): number {
  return Number.isInteger(page) && page >= 1 ? page : 1;
}

export function clampSize(size: number): number {
  if (!Number.isInteger(size) || size < 1) {
    return DEFAULT_PAGE_SIZE;
  }
  return Math.min(size, MAX_PAGE_SIZE);
}

/** „Afișez 21–40 din 45” pentru pagina curentă. */
export function rangeLabel(page: number, size: number, totalItems: number): string {
  if (totalItems === 0) {
    return '0 rezultate';
  }
  const first = (page - 1) * size + 1;
  const last = Math.min(page * size, totalItems);
  return `${first}–${last} din ${totalItems}`;
}

function parseLocalDay(day: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!match) {
    return null;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

function toIsoSeconds(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function validIso(value: unknown): string | null {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) ? value : null;
}
