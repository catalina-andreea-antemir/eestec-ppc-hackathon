// handleSummary comun: scrie results/<scenariu>-<moment>.json si .txt si afiseaza rezumatul text.
// Fara dependinte externe (jslib), ca testele sa mearga si fara internet.

function fmt(v) {
  if (v === undefined || v === null) return '-';
  return typeof v === 'number' ? (Math.round(v * 100) / 100).toString() : String(v);
}

function thresholdLines(data) {
  const out = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    if (!m.thresholds) continue;
    for (const [expr, t] of Object.entries(m.thresholds)) {
      out.push(`  ${t.ok ? 'OK  ' : 'FAIL'} ${name}: ${expr}`);
    }
  }
  return out.sort();
}

function trendLines(data) {
  const out = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    if (m.type !== 'trend' || !/^(http_req_duration|filegrid_op_ms)\{/.test(name)) continue;
    const v = m.values;
    out.push(`  ${name.padEnd(58)} n/a  avg=${fmt(v.avg)}  p90=${fmt(v['p(90)'])}  p95=${fmt(v['p(95)'])}  max=${fmt(v.max)} ms`);
  }
  return out.sort();
}

function counter(data, name) {
  const m = data.metrics[name];
  return m ? m.values.count : 0;
}

function rate(data, name) {
  const m = data.metrics[name];
  return m ? m.values.rate : undefined;
}

export function makeSummary(scenario, extra) {
  return function handleSummary(data) {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
    const dur = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
    const checks = data.metrics.checks ? data.metrics.checks.values : { passes: 0, fails: 0 };
    const thr = thresholdLines(data);
    const failed = thr.filter((l) => l.indexOf('FAIL') >= 0).length;
    const lines = [
      `FileGrid k6 - ${scenario} - ${new Date().toISOString()}`,
      `Rezultat: ${failed === 0 ? 'TRECUT' : `ESUAT (${failed} praguri depasite)`}`,
      '',
      `Cereri HTTP: ${reqs}   iteratii: ${counter(data, 'iterations')}`,
      `Latenta totala: avg=${fmt(dur.avg)} p90=${fmt(dur['p(90)'])} p95=${fmt(dur['p(95)'])} max=${fmt(dur.max)} ms`,
      `http_req_failed: ${fmt((rate(data, 'http_req_failed') || 0) * 100)}%   erori neasteptate (filegrid_unexpected): ${fmt((rate(data, 'filegrid_unexpected') || 0) * 100)}%`,
      `Verificari (checks): ${checks.passes} trecute, ${checks.fails} picate`,
      `429 bulkhead (CATEGORY_SATURATED, nu sunt erori): ${counter(data, 'filegrid_bulkhead_429')}`,
      `403 asteptate (securitate): ${counter(data, 'filegrid_forbidden_403')}`,
      `Loturi duplicate confirmate (200): ${counter(data, 'filegrid_meter_duplicates')}`,
      `Nepotriviri de hash la descarcare: ${counter(data, 'filegrid_integrity_mismatch')}`,
    ];
    if (extra) lines.push('', ...extra(data));
    lines.push('', 'Praguri:', ...(thr.length ? thr : ['  (niciunul)']));
    const trends = trendLines(data);
    if (trends.length) lines.push('', 'Latente pe operatie:', ...trends.map((l) => l.replace(' n/a ', ' ')));
    const text = lines.join('\n') + '\n';
    return {
      stdout: '\n' + text,
      [`results/${scenario}-${stamp}.txt`]: text,
      [`results/${scenario}-${stamp}.json`]: JSON.stringify(data, null, 2),
    };
  };
}
