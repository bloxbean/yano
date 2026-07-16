'use strict';

const text = (value) => value === null || value === undefined ? '—' : String(value);

function fillList(id, values) {
  const root = document.getElementById(id);
  root.replaceChildren();
  for (const [name, value] of Object.entries(values || {})) {
    const term = document.createElement('dt');
    term.textContent = name.replaceAll(/([A-Z])/g, ' $1');
    const description = document.createElement('dd');
    description.textContent = text(value);
    root.append(term, description);
  }
}

function render(report) {
  const summary = document.getElementById('summary');
  summary.replaceChildren();
  const result = document.createElement('strong');
  result.className = report.outcome === 'PASS' ? 'pass' : 'fail';
  result.textContent = report.outcome;
  const label = document.createElement('span');
  const failure = report.outcome === 'FAIL' && report.failureCode ?
    ` · ${text(report.failureCode)}` : '';
  label.textContent = ` ${report.evidenceId} · ${report.finishedAt}${failure}`;
  summary.append(result, label);
  fillList('chain', report.chain);
  fillList('storage', report.storage);
  fillList('kafka', report.kafka);
  fillList('anchor', report.anchor);
  const checks = document.getElementById('checks');
  checks.replaceChildren();
  for (const check of report.checks || []) {
    const item = document.createElement('li');
    item.textContent = `${check.status} · ${check.name}`;
    item.className = check.status === 'PASS' ? 'pass' :
      check.status === 'FAIL' ? 'fail' : 'neutral';
    checks.append(item);
  }
}

async function refresh() {
  try {
    const response = await fetch('/api/v1/reports/latest', {cache: 'no-store'});
    if (response.ok) render(await response.json());
  } catch (_) {
    // The next bounded poll retries; external text is never reflected.
  }
}

refresh();
setInterval(refresh, 3000);
