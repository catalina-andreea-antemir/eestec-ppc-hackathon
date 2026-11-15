import { Params } from '@angular/router';

import { ALL_CATEGORIES, Category, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, SearchFilters } from './files.models';

/**
 * Pure search helpers: filters <-> GET /files parameters <-> page URL parameters.
 * Kept out of the component so they are easy to test.
 */

/** GET /files parameters; empty ones are omitted so the server applies its defaults. */
export function toApiParams(filters: SearchFilters): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.category) {
    params['category'] = filters.category;
  }
  const q = filters.q.trim();
  // Connection document names are encrypted on the server, so q has no effect; do not send it.
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
 * The day picked in `<input type="date">` (YYYY-MM-DD, browser local time) -> the start of that day
 * in UTC. `from` is inclusive.
 */
export function dayStartIso(day: string): string | null {
  const date = parseLocalDay(day);
  return date ? toIsoSeconds(date) : null;
}

/**
 * The "to" day is inclusive in the UI, but `to` is exclusive in the contract:
 * send the start of the next day.
 */
export function dayEndExclusiveIso(day: string): string | null {
  const date = parseLocalDay(day);
  if (!date) {
    return null;
  }
  date.setDate(date.getDate() + 1);
  return toIsoSeconds(date);
}

/** Inverse of dayStartIso: UTC instant -> local day, for the date input. */
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

/** Inverse of dayEndExclusiveIso. */
export function isoToInclusiveDay(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  date.setDate(date.getDate() - 1);
  return isoToDay(date.toISOString());
}

/** Filters in the page URL (/search?category=...&page=2), so refreshes and links keep them. */
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

/** Reads the filters from the URL; invalid values are ignored (defaults are used). */
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

/** "21-40 of 45" for the current page. */
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
