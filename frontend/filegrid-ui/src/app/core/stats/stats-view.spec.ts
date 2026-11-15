import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { ALL_CATEGORIES } from '../files/files.models';
import { OutageZones, Requirement, Requirements, StorageAdvisor, StorageScore, ZoneRank } from './stats.models';
import {
  changeLevel,
  comparisonRows,
  describeStatsError,
  describeStatsFailure,
  formatMinutes,
  formatPeakHour,
  formatRatio,
  formatShare,
  hasOutageData,
  NO_VALUE,
  policyInterpretation,
  policyRows,
  UNAVAILABLE_REASONS,
  requirementsInterpretation,
  requirementTone,
  sortAnomalies,
  zoneBars,
} from './stats-view';
import { canViewStats } from './stats.models';

function cell(category: StorageScore['category'], storage: StorageScore['storage'], score: number): StorageScore {
  return { category, storage, score, latencyP50Ms: 1, latencyP95Ms: 2, errorRatePercent: 0, relativeCostPerGb: 1, samples: 1, benchmarked: true };
}

function fullMatrix(): StorageScore[] {
  // Deliberately shuffled order: the UI has to put cells in place itself.
  return ALL_CATEGORIES.flatMap((c) => [cell(c, 'DB', 50), cell(c, 'FTP', 70), cell(c, 'S3', 60)]);
}

function advisor(partial: Partial<StorageAdvisor> = {}): StorageAdvisor {
  return {
    generatedAt: '2026-11-14T13:05:00Z',
    basis: {},
    costAssumption: { unit: 'cost relativ per GB (FTP = 1,0)', FTP: 1, S3: 1.4, DB: 3.2 },
    matrix: fullMatrix(),
    recommendations: [],
    interpretation: 'x',
    ...partial,
  };
}

function req(id: Requirement['id'], status: string, title = 'Titlu', message = 'mesaj.'): Requirement {
  return { id, category: 'METER_READING', title, status: status as Requirement['status'], message, thresholds: {}, current: {} };
}

describe('stats-view', () => {
  describe('policyRows (doar stocarea din politică)', () => {
    const rec = (category: StorageScore['category'], policy: StorageScore['storage'], recommended: StorageScore['storage']) => ({
      category,
      recommendedStorage: recommended,
      currentPolicyStorage: policy,
      matchesPolicy: policy === recommended,
      rationale: 'S3 are cel mai bun scor.',
    });

    it('arată storage-ul din politică chiar când recomandarea diferă, cu cifrele lui din benchmark', () => {
      const rows = policyRows(advisor({ recommendations: [rec('METER_READING', 'DB', 'FTP')] }));
      expect(rows.map((r) => r.category)).toEqual([...ALL_CATEGORIES]);
      expect(rows[0].storage).toBe('DB');
      expect(rows[0].measured).toMatchObject({ category: 'METER_READING', storage: 'DB', score: 50 });
      expect(Object.keys(rows[0]).sort()).toEqual(['category', 'label', 'measured', 'storage']);
    });

    it('categorie fără răspuns de la server sau fără măsurători → null, nu altă stocare', () => {
      const rows = policyRows(
        advisor({
          recommendations: [rec('METER_READING', 'S3', 'S3')],
          matrix: fullMatrix().filter((c) => !(c.category === 'METER_READING' && c.storage === 'S3')),
        }),
      );
      expect(rows[0]).toMatchObject({ storage: 'S3', measured: null });
      expect(rows[1]).toMatchObject({ storage: null, measured: null });
    });

    it('policyInterpretation: propoziție neutră, fără „recomand” sau „schimb”', () => {
      const rows = policyRows(
        advisor({ recommendations: [rec('METER_READING', 'DB', 'S3'), rec('INSPECTION_VIDEO', 'FTP', 'S3')] }),
      );
      const text = policyInterpretation(rows, { FTP: 'FTP', S3: 'S3', DB: 'Bază de date' })!;
      expect(text).toBe(
        'Politica actuală: Citiri contoare → Bază de date, Video-uri dronă → FTP; ' +
          'cifrele sunt din benchmark-ul pe cele 12 combinații categorie × stocare.',
      );
      expect(text).not.toMatch(/recomand|schimb/i);
      expect(policyInterpretation(policyRows(advisor()), { FTP: 'FTP', S3: 'S3', DB: 'Bază de date' })).toBeNull();
    });
  });
  it('changeLevel: sub 10% stabil, sub 50% moderat, altfel ridicat', () => {
    expect(changeLevel(4.3)).toBe('stable');
    expect(changeLevel(-12)).toBe('moderate');
    expect(changeLevel(108.8)).toBe('high');
  });

  it('comparisonRows scalează barele pe rând și calculează de câte ori au crescut cererile', () => {
    const stats = (requests: number, p95: number) => ({
      requests, throughputPerMinute: 0, latencyP50Ms: 0, latencyP95Ms: p95, latencyP99Ms: 0, errorRatePercent: 0, rejected: 0,
    });
    const [row] = comparisonRows([{ category: 'OUTAGE_RECORD', normal: stats(36, 340), peak: stats(480, 710), p95ChangePercent: 108.8 }]);
    expect(row.peakBar).toBe(100);
    expect(row.normalBar).toBeCloseTo((340 / 710) * 100);
    expect(row.requestsRatio).toBeCloseTo(13.33, 1);
    expect(row.level).toBe('high');
  });

  it('sortAnomalies pune primele abaterile cele mai mari, fără să modifice lista primită', () => {
    const a = { meterId: 'A', zone: 'Banat' as const, readingTs: '', kwh: 1, median: 0, stddev: 0, threshold: 0, deviations: 3 };
    const b = { ...a, meterId: 'B', deviations: 15 };
    const input = [a, b];
    expect(sortAnomalies(input).map((x) => x.meterId)).toEqual(['B', 'A']);
    expect(input[0].meterId).toBe('A');
  });

  it('formatMinutes', () => {
    expect(formatMinutes(45)).toBe('45 min');
    expect(formatMinutes(155)).toBe('2 h 35 min');
    expect(formatMinutes(120)).toBe('2 h');
  });

  describe('zoneBars', () => {
    const z = (zone: ZoneRank['zone'], rank: number, outages: number): ZoneRank => ({
      rank, zone, outages, sharePercent: 0, vsAverageRatio: 0, peakHour: 9,
    });

    it('sortează descrescător după avarii, indiferent de ordinea din răspuns', () => {
      const bars = zoneBars([z('Banat', 3, 23), z('Muntenia', 1, 78), z('Dobrogea', 2, 41)], []);
      expect(bars.map((b) => b.zone.zone)).toEqual(['Muntenia', 'Dobrogea', 'Banat']);
      expect(bars[0].width).toBe(100);
      expect(bars[2].width).toBeCloseTo((23 / 78) * 100);
    });

    it('pune avariile pe cele 24 de ore ale zonei', () => {
      const [bar] = zoneBars([z('Muntenia', 1, 31)], [
        { zone: 'Muntenia', hour: 8, outages: 12 },
        { zone: 'Muntenia', hour: 9, outages: 19 },
        { zone: 'Banat', hour: 9, outages: 5 },
      ]);
      expect(bar.hours).toHaveLength(24);
      expect(bar.hours[8]).toBe(12);
      expect(bar.hours[9]).toBe(19);
      expect(bar.hours.reduce((s, n) => s + n, 0)).toBe(31);
    });
  });

  describe('cerințe', () => {
    it('requirementTone: OK verde, AT_RISK și valori necunoscute roșu', () => {
      expect(requirementTone({ status: 'OK' })).toBe('green');
      expect(requirementTone({ status: 'AT_RISK' })).toBe('red');
      expect(requirementTone({ status: 'UNKNOWN' as Requirement['status'] })).toBe('red');
    });

    it('folosește interpretarea serverului când există', () => {
      const res: Requirements = { generatedAt: '', requirements: [req('R1', 'OK')], interpretation: 'De la server.' };
      expect(requirementsInterpretation(res)).toBe('De la server.');
    });

    it('fără interpretare de la server o compune din plăcuțe', () => {
      const all = (s: string): Requirements => ({
        generatedAt: '',
        requirements: [req('R1', 'OK'), req('R2', s, 'Avariile sunt disponibile imediat', 'p95 2,4 s.'), req('R3', 'OK'), req('R4', 'OK')],
      });
      expect(requirementsInterpretation(all('OK'))).toContain('Toate cele 4 cerințe');
      const text = requirementsInterpretation(all('AT_RISK'));
      expect(text).toContain('3 din 4 cerințe sunt îndeplinite; una e în pericol.');
      expect(text).toContain('R2 (avariile sunt disponibile imediat): p95 2,4 s.');
    });

    describe('date insuficiente (current.sufficientData === false)', () => {
      const withCurrent = (status: string, current: Record<string, unknown>): Requirement => ({ ...req('R4', status), current });

      it('OK cu date insuficiente → gri (neutral), nu verde', () => {
        expect(requirementTone(withCurrent('OK', { samples: 3, sufficientData: false }))).toBe('neutral');
      });

      it('AT_RISK rămâne roșu chiar dacă datele sunt puține (alerta are prioritate)', () => {
        expect(requirementTone(withCurrent('AT_RISK', { samples: 2, sufficientData: false }))).toBe('red');
      });

      it('lipsa câmpului sau sufficientData: true → normal (verde)', () => {
        expect(requirementTone(withCurrent('OK', {}))).toBe('green');
        expect(requirementTone(withCurrent('OK', { sufficientData: true }))).toBe('green');
        expect(requirementTone({ status: 'OK' })).toBe('green');
      });

      it('interpretarea compusă nu spune „îndeplinită” pentru cerința fără date', () => {
        const res: Requirements = {
          generatedAt: '',
          requirements: [
            req('R1', 'OK'),
            req('R2', 'OK'),
            req('R3', 'OK'),
            {
              ...req('R4', 'OK', 'Transferul rezistă pe conexiuni lente', 'Date insuficiente: 3 din minim 5 transferuri video.'),
              current: { samples: 3, sufficientData: false },
            },
          ],
        };
        const text = requirementsInterpretation(res);
        expect(text).not.toContain('Toate cele 4');
        expect(text).toContain('3 din 4 cerințe sunt îndeplinite; una nu are încă date suficiente pentru un verdict.');
        expect(text).toContain('R4 (transferul rezistă pe conexiuni lente): Date insuficiente: 3 din minim 5 transferuri video.');
        expect(text).toContain('Nicio cerință nu e în pericol');
      });

      it('interpretarea compusă cu o cerință roșie și una gri le numește pe amândouă', () => {
        const res: Requirements = {
          generatedAt: '',
          requirements: [
            req('R1', 'OK'),
            req('R2', 'AT_RISK', 'Avarii', 'p95 2,4 s.'),
            req('R3', 'OK'),
            { ...req('R4', 'OK'), current: { sufficientData: false } },
          ],
        };
        const text = requirementsInterpretation(res);
        expect(text).toContain('2 din 4 cerințe sunt îndeplinite; una e în pericol; una nu are încă date suficiente');
        expect(text).toContain('Recomandăm verificarea acum');
      });
    });
  });

  it('canViewStats: doar DISPATCH și ADMIN', () => {
    expect(canViewStats('DISPATCH')).toBe(true);
    expect(canViewStats('ADMIN')).toBe(true);
    expect(canViewStats('FIELD')).toBe(false);
    expect(canViewStats('CONNECTIONS')).toBe(false);
    expect(canViewStats('METER_SERVICE')).toBe(false);
    expect(canViewStats(null)).toBe(false);
  });

  it('describeStatsError: 403 explică rolurile, 404/501 = încă neimplementat', () => {
    const http = (status: number, body: object | null = null) =>
      new HttpErrorResponse({ status, error: body, headers: new HttpHeaders() });
    expect(describeStatsError(http(403, { status: 403, code: 'FORBIDDEN', message: 'm', traceId: 't1' }))).toContain(
      'doar Dispecerat și Administrator',
    );
    expect(describeStatsError(http(404))).toBe('Funcția aceasta nu este încă disponibilă pe server.');
    expect(describeStatsError(http(501))).toBe('Funcția aceasta nu este încă disponibilă pe server.');
    expect(describeStatsError(http(0))).toContain('Serverul nu răspunde');
  });

  describe('null din O7 (interval fără avarii) e altceva decât 0', () => {
    const zones = (totalOutages: number, ranking: ZoneRank[]): OutageZones => ({
      generatedAt: '', from: '', to: '', totalOutages, ranking, byHour: [], interpretation: '',
    });
    const empty = (zone: ZoneRank['zone']): ZoneRank => ({
      rank: 1, zone, outages: 0, sharePercent: null, vsAverageRatio: null, peakHour: null,
    });

    it('null se afișează „—”, niciodată 0, NaN sau „null”', () => {
      for (const text of [formatShare(null), formatRatio(null), formatPeakHour(null)]) {
        expect(text).toBe(NO_VALUE);
        expect(text).not.toMatch(/0|NaN|null/);
      }
    });

    it('0 real se afișează ca 0', () => {
      expect(formatShare(0)).toBe('0%');
      expect(formatRatio(0)).toBe('×0,00');
      expect(formatPeakHour(0)).toBe('00:00');
      expect(formatShare(54.9)).toBe('54,9%');
      expect(formatRatio(1.65)).toBe('×1,65');
    });

    it('hasOutageData: fals pentru total 0, clasament gol sau toate câmpurile null', () => {
      expect(hasOutageData(zones(0, [empty('Muntenia'), empty('Dobrogea'), empty('Banat')]))).toBe(false);
      expect(hasOutageData(zones(5, []))).toBe(false);
      expect(hasOutageData(zones(5, [empty('Muntenia'), empty('Banat')]))).toBe(false);
    });

    it('hasOutageData: adevărat când o zonă are 0 avarii, dar intervalul are avarii', () => {
      const res = zones(78, [
        { rank: 1, zone: 'Muntenia', outages: 78, sharePercent: 100, vsAverageRatio: 3, peakHour: 9 },
        { rank: 2, zone: 'Banat', outages: 0, sharePercent: 0, vsAverageRatio: 0, peakHour: null },
      ]);
      expect(hasOutageData(res)).toBe(true);
      // The zone without outages stays in the ranking, with an empty bar and no peak hour marked.
      const banat = zoneBars(res.ranking, res.byHour).find((b) => b.zone.zone === 'Banat')!;
      expect(banat.width).toBe(0);
      expect(banat.hours.every((h) => h === 0)).toBe(true);
    });
  });

  describe('describeStatsFailure: 503 fără măsurători = indisponibil temporar, nu eroare', () => {
    const http503 = (details?: object, retryAfterSeconds?: number) =>
      new HttpErrorResponse({
        status: 503,
        error: { status: 503, code: 'STORAGE_UNAVAILABLE', message: 'm', traceId: 't9', retryAfterSeconds, details },
        headers: new HttpHeaders(),
      });

    it.each(['REQUEST_METRICS_UNAVAILABLE', 'STORAGE_MEASUREMENTS_INCOMPLETE'])('%s → mesaj informativ', (reason) => {
      const f = describeStatsFailure(http503({ reason }));
      expect(f.kind).toBe('unavailable');
      expect(f.message).toBe(UNAVAILABLE_REASONS[reason]);
      expect(f.message).toContain('nu este încă disponibil');
      expect(f.message).toContain('Va apărea automat');
    });

    it('alt 503 rămâne eroare, cu Retry-After', () => {
      const f = describeStatsFailure(http503({ reason: 'ALTCEVA' }, 30));
      expect(f.kind).toBe('error');
      expect(f.message).toContain('reîncearcă peste 30 s');
      expect(describeStatsFailure(http503(undefined, 5)).kind).toBe('error');
    });

    it('reason-ul contează doar la 503', () => {
      const err = new HttpErrorResponse({
        status: 500,
        error: { status: 500, code: 'INTERNAL', message: 'Eroare', traceId: 't', details: { reason: 'REQUEST_METRICS_UNAVAILABLE' } },
      });
      expect(describeStatsFailure(err).kind).toBe('error');
    });
  });
});
