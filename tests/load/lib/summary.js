// Shared handleSummary: writes results/<scenario>-<timestamp>.json and .txt and prints the text summary.
// No external dependencies (jslib), so the tests also run offline.

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
      `Result: ${failed === 0 ? 'PASSED' : `FAILED (${failed} thresholds exceeded)`}`,
      '',
      `HTTP requests: ${reqs}   iterations: ${counter(data, 'iterations')}`,
      `Overall latency: avg=${fmt(dur.avg)} p90=${fmt(dur['p(90)'])} p95=${fmt(dur['p(95)'])} max=${fmt(dur.max)} ms`,
      `http_req_failed: ${fmt((rate(data, 'http_req_failed') || 0) * 100)}%   unexpected errors (filegrid_unexpected): ${fmt((rate(data, 'filegrid_unexpected') || 0) * 100)}%`,
      `Checks: ${checks.passes} passed, ${checks.fails} failed`,
      `429 bulkhead (CATEGORY_SATURATED, not errors): ${counter(data, 'filegrid_bulkhead_429')}`,
      `403 expected (security): ${counter(data, 'filegrid_forbidden_403')}`,
      `Confirmed duplicate batches (200): ${counter(data, 'filegrid_meter_duplicates')}`,
      `Download hash mismatches: ${counter(data, 'filegrid_integrity_mismatch')}`,
    ];
    if (extra) lines.push('', ...extra(data));
    lines.push('', 'Thresholds:', ...(thr.length ? thr : ['  (none)']));
    const trends = trendLines(data);
    if (trends.length) lines.push('', 'Latency per operation:', ...trends.map((l) => l.replace(' n/a ', ' ')));
    const text = lines.join('\n') + '\n';
    return {
      stdout: '\n' + text,
      [`results/${scenario}-${stamp}.txt`]: text,
      [`results/${scenario}-${stamp}.json`]: JSON.stringify(data, null, 2),
    };
  };
}
