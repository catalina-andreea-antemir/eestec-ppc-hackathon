import { EMPTY_FILTERS, searchableCategories, SearchFilters } from './files.models';
import {
  clampSize,
  dayEndExclusiveIso,
  dayStartIso,
  fromQueryParams,
  isoToDay,
  isoToInclusiveDay,
  rangeLabel,
  toApiParams,
  toQueryParams,
} from './search-query';

describe('toApiParams (parametrii GET /files)', () => {
  it('trimite doar filtrele completate, plus page și size', () => {
    expect(toApiParams(EMPTY_FILTERS)).toEqual({ page: '1', size: '20' });
  });

  it('trimite categoria, textul și intervalul', () => {
    const filters: SearchFilters = {
      category: 'OUTAGE_RECORD',
      q: '  BRASOV ',
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-15T00:00:00Z',
      page: 3,
      size: 50,
    };
    expect(toApiParams(filters)).toEqual({
      category: 'OUTAGE_RECORD',
      q: 'BRASOV',
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-15T00:00:00Z',
      page: '3',
      size: '50',
    });
  });

  it('nu trimite textul la documente de racordare (numele e criptat pe server)', () => {
    expect(toApiParams({ ...EMPTY_FILTERS, category: 'CONNECTION_DOC', q: 'popescu' })['q']).toBeUndefined();
  });

  it('limitează size la 100 și page la minim 1', () => {
    expect(toApiParams({ ...EMPTY_FILTERS, size: 500, page: 0 })).toEqual({ page: '1', size: '100' });
    expect(clampSize(0)).toBe(20);
  });
});

describe('intervalul de date', () => {
  it('„de la” = începutul zilei locale, „până la” inclusiv = începutul zilei următoare (to e exclusiv)', () => {
    const from = dayStartIso('2026-11-14')!;
    const to = dayEndExclusiveIso('2026-11-14')!;
    expect(Date.parse(to) - Date.parse(from)).toBe(24 * 3600 * 1000);
    expect(new Date(from).getHours()).toBe(0);
    expect(isoToDay(from)).toBe('2026-11-14');
    expect(isoToInclusiveDay(to)).toBe('2026-11-14');
  });

  it('o zi invalidă nu produce filtru', () => {
    expect(dayStartIso('')).toBeNull();
    expect(dayEndExclusiveIso('14.11.2026')).toBeNull();
  });
});

describe('filtrele în URL', () => {
  it('se citesc înapoi la fel cum s-au scris', () => {
    const filters: SearchFilters = { category: 'INSPECTION_VIDEO', q: 'tronson', from: '2026-11-01T00:00:00Z', to: null, page: 2, size: 50 };
    const params = Object.fromEntries(Object.entries(toQueryParams(filters)).filter(([, v]) => v !== null));
    expect(fromQueryParams(params)).toEqual(filters);
  });

  it('ignoră valorile greșite din URL', () => {
    expect(fromQueryParams({ category: 'ALTCEVA', page: '-2', size: 'mult', from: 'ieri' })).toEqual(EMPTY_FILTERS);
  });
});

describe('paginare', () => {
  it('arată intervalul de rezultate al paginii', () => {
    expect(rangeLabel(1, 20, 45)).toBe('1–20 din 45');
    expect(rangeLabel(3, 20, 45)).toBe('41–45 din 45');
    expect(rangeLabel(1, 20, 0)).toBe('0 rezultate');
  });
});

describe('searchableCategories (matricea de roluri 4.4)', () => {
  it('fiecare rol vede doar categoriile lui', () => {
    expect(searchableCategories('FIELD')).toEqual(['OUTAGE_RECORD', 'INSPECTION_VIDEO']);
    expect(searchableCategories('DISPATCH')).toEqual(['METER_READING', 'OUTAGE_RECORD']);
    expect(searchableCategories('CONNECTIONS')).toEqual(['CONNECTION_DOC']);
    expect(searchableCategories('ADMIN')).toHaveLength(4);
    expect(searchableCategories('METER_SERVICE')).toEqual([]);
    expect(searchableCategories(null)).toEqual([]);
  });
});
