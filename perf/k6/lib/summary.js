import { RESULTS_DIR } from './config.js';

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmt = (v) => (typeof v === 'number' && !Number.isInteger(v) ? v.toFixed(3) : String(v));

// Writes <name>.json and <name>.html under RESULTS_DIR and prints a short text summary.
export function summaryFiles(name, data) {
  const metrics = Object.entries(data.metrics).sort(([a], [b]) => a.localeCompare(b));
  const failed = [];
  for (const [metric, m] of metrics) {
    for (const [expr, t] of Object.entries(m.thresholds || {})) {
      if (!t.ok) failed.push(`${metric} ${expr}`);
    }
  }
  const verdict = failed.length ? `THRESHOLDS FAILED: ${failed.join('; ')}` : 'THRESHOLDS OK';
  const lines = metrics.map(([metric, m]) =>
    `${metric}: ${Object.entries(m.values).map(([k, v]) => `${k}=${fmt(v)}`).join(' ')}`);
  const rows = metrics.map(([metric, m]) =>
    `<tr><td>${esc(metric)}</td><td>${esc(Object.entries(m.values).map(([k, v]) => `${k}=${fmt(v)}`).join(' '))}</td></tr>`).join('');
  const html = `<!doctype html><meta charset="utf-8"><title>${esc(name)}</title>` +
    `<h1>${esc(name)}</h1><p>${esc(verdict)}</p><table border="1" cellpadding="4"><tr><th>metric</th><th>values</th></tr>${rows}</table>`;
  return {
    [`${RESULTS_DIR}/${name}.json`]: JSON.stringify(data, null, 2),
    [`${RESULTS_DIR}/${name}.html`]: html,
    stdout: `== ${name} ==\n${verdict}\n${lines.join('\n')}\n`,
  };
}
