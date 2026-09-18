/**
 * Data Health — the app checking its own data, and saying what it could not check.
 *
 * <p>Everything comes from one DB-only request. The screen's whole reason for existing is
 * that the bug this codebase actually suffers from is invisible: a number that was never
 * measured, drawn as though it were. Three of those have run for months here, and none was
 * found by looking at a screen — they were found by asking how many stocks a signal was
 * measured on and whether the answer varied. This page asks both, every time it loads.
 *
 * <p>Note the ordering rule: problems first, then what to keep an eye on, then the known
 * causes — and then EVERY check including the ones that passed. A page that showed only
 * alarms would read as "all clear" when it equally means "nothing ran".
 */

import { initChrome, registerRefresh } from './nav.js';
import { el, section, card, table, kpi, alert, mount, empty, withCount, withSummary} from './ui.js';
import { get } from './api.js';

const view = document.getElementById('view');

/** ui.js maps its own severity words to CSS classes; these are ours in its vocabulary. */
const ALERT_SEVERITY = {
  PROBLEM: 'HIGH',
  WATCH: 'MEDIUM',
  KNOWN: 'INFO',
  OK: 'OPPORTUNITY',
};

const LABEL = {
  PROBLEM: 'Problem',
  WATCH: 'Worth a look',
  KNOWN: 'Known cause',
  OK: 'Passed',
};

const BADGE_CLASS = {
  PROBLEM: 'danger',
  WATCH: 'warning',
  KNOWN: 'info',
  OK: 'success',
};

const CHECK_LABEL = {
  freshness: 'Freshness',
  coverage: 'Signal',
  history: 'History',
};

/** Reading order: act on these, then watch these, then the rest. */
const ORDER = ['PROBLEM', 'WATCH', 'KNOWN', 'OK'];

function summaryRow(s) {
  return el('div.grid.kpis', {},
    kpi({
      label: 'Need attention',
      value: String(s.problems),
      sub: s.problems === 0 ? 'nothing is failing a check' : 'listed first below',
      tone: s.problems > 0 ? 'bad' : 'good',
    }),
    kpi({
      label: 'Worth a look',
      value: String(s.watch),
      sub: 'thin, stale by one session, or unvarying',
      tone: 'neutral',
    }),
    kpi({
      label: 'Known cause',
      value: String(s.known),
      sub: 'odd-looking, already explained',
      tone: 'neutral',
    }),
    kpi({
      label: 'Checks run',
      value: String(s.checks),
      sub: `${s.ok} passed`,
      tone: 'neutral',
    }));
}

function findingAlert(f) {
  return alert({
    severity: ALERT_SEVERITY[f.severity] || 'INFO',
    title: `${CHECK_LABEL[f.check] || f.check} · ${f.subject} — ${f.headline}`,
    message: f.detail,
    meta: f.figure,
  });
}

function group(findings, severity) {
  const rows = findings.filter((f) => f.severity === severity);
  if (rows.length === 0) return null;
  return el('div', { style: 'display:flex;flex-direction:column;gap:10px' },
    ...rows.map(findingAlert));
}

function allChecksTable(findings) {
  const rank = (f) => ORDER.indexOf(f.severity);
  return table([
    {
      key: 'check',
      label: 'Check',
      value: (r) => CHECK_LABEL[r.check] || r.check,
      render: (r) => el('span', {}, CHECK_LABEL[r.check] || r.check),
    },
    { key: 'subject', label: 'What was checked', value: (r) => r.subject },
    {
      key: 'severity',
      label: 'Result',
      value: rank,
      render: (r) => el('span.badge.' + (BADGE_CLASS[r.severity] || 'neutral'), {},
        LABEL[r.severity] || r.severity),
    },
    { key: 'headline', label: 'Verdict', value: (r) => r.headline },
    { key: 'figure', label: 'Measured on', value: (r) => r.figure },
  ], findings, { sortKey: 'severity', sortDir: 'asc', emptyMessage: 'No checks ran at all.' });
}

function notCheckedCard(items) {
  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:8px' },
      'An all-clear above means every check the app can run on itself came back clean. '
      + 'It is not a guarantee that the numbers are right, and these are the gaps:'),
    el('ul', { style: 'margin:0;padding-left:20px;font-size:13px;line-height:1.7' },
      ...items.map((t) => el('li', {}, t))));
}

function paint(data) {
  const findings = data.findings || [];

  const acting = ORDER.slice(0, 3)
    .map((sev) => group(findings, sev))
    .filter(Boolean);

  mount(view,
    withSummary(section('Where the data stands',
      `Checked ${data.today}. ${data.note}`,
      summaryRow(data.summary)),
    // The headline verdict. This whole screen exists because a value nobody measured can look
    // exactly like a measured one — so its own heading must not be where that happens. Folded,
    // this chip is the entire answer to "is anything broken?".
    data.summary.problems ? `${data.summary.problems} failing`
      : data.summary.watch ? `${data.summary.watch} to watch` : 'All clear',
    { type: data.summary.problems ? 'danger' : data.summary.watch ? 'warning' : 'success' }),

    withCount(section('What needs your attention',
      'Ordered by how much it matters: things that are failing, then things worth watching, '
      + 'then things that look odd for a reason already recorded in BUGS.md.',
      acting.length
        ? el('div', { style: 'display:flex;flex-direction:column;gap:14px' }, ...acting)
        : empty('Nothing is failing a check',
          'Every automated check passed. Read the list below to see what that covered — and '
          + 'the gaps underneath it, which no check can close.')),
    findings.filter((f) => ORDER.slice(0, 3).includes(f.severity)).length),

    withCount(section('Every check that ran',
      'Including the ones that passed. The figure column is the number each verdict was '
      + 'reached on, so you can disagree with it.',
      allChecksTable(findings)), findings.length),

    withCount(section('What this screen cannot check',
      'The honest limits.',
      notCheckedCard(data.notChecked || [])), (data.notChecked || []).length));
}

async function render() {
  mount(view, section('Data Health', 'Checking…', card(el('div.muted', {}, 'Loading…'))));
  const { data } = await get('/api/dashboard/data-health');
  paint(data);
}

/**
 * One entry point, so the refresh control can re-run the whole screen.
 *
 * On this page a refresh is worth more than elsewhere: every check here is computed on read,
 * so clicking it genuinely re-runs them rather than re-reading a stored answer.
 */
async function reload() {
  await initChrome();
  await render();
}

// Registered after the first run, so a click mid-load cannot start a second one.
reload().then(() => registerRefresh(reload)).catch((e) => {
  mount(view, section('Data Health', 'Could not run the checks',
    card(el('div', {},
      'The app did not answer. That is itself a finding — nothing on any other screen is '
      + 'live either. Start it with start-app.bat and reload.'),
      el('div.muted', { style: 'margin-top:6px;font-size:12px' }, String(e && e.message ? e.message : e)))));
});
