/**
 * Reports — the scheduled email reports, viewable on screen.
 *
 * These are served as HTML by /api/reports/{name}/preview and shown in an iframe, because
 * their analysis is welded to their markup on the Java side (SPEC 27.6). The upside is that
 * what you see here is byte-identical to what lands in your inbox — it cannot drift.
 *
 * All click-only: a briefing build calls NSE and the AI provider, and a holdings report
 * re-runs the whole analysis pipeline. Nothing here loads automatically.
 */

import { initChrome } from './nav.js';
import { el, section, empty, mount, costNote } from './ui.js';

const view = document.getElementById('view');

const REPORTS = [
  {
    name: 'holdings-actions',
    label: 'Holdings 1 of 2: Action Items',
    explain: 'The decisions worth making today — exits, profit-booking with the tax position accounted for, and holdings whose original case is weakening. This is the one to read if you only read one.',
  },
  {
    name: 'holdings-analysis',
    label: 'Holdings 2 of 2: Analysis',
    explain: 'The deeper context — valuation, price levels, machine-learning views, balance-sheet quality and how your portfolio sits against the wider market. Read when you have time, not in a hurry.',
  },
  {
    name: 'holdings-weekly',
    label: 'Weekly Portfolio Review',
    explain: 'The Saturday review: week-on-week changes, best and worst movers, and how each sector contributed.',
  },
  {
    name: 'morning-briefing',
    label: 'Morning Briefing',
    explain: 'The pre-market picture — institutional flows, your portfolio summary, holdings sitting near a key level, and the multibagger radar.',
  },
];

let current = null;

function frame() {
  const iframe = el('iframe#report-frame', {
    title: 'Report preview',
    style: 'width:100%;height:74vh;border:1px solid var(--rule);border-radius:8px;background:#fff',
  });
  return iframe;
}

function buttons(onPick) {
  const bar = el('div.row.wrap', { style: 'gap:8px;margin-bottom:6px' });
  for (const r of REPORTS) {
    const btn = el('button.action' + (current === r.name ? '' : '.secondary'), {}, r.label);
    btn.addEventListener('click', () => onPick(r));
    bar.append(btn);
  }
  return bar;
}

function paint() {
  const host = el('div', {});
  const explainHost = el('div', {});
  const frameHost = el('div', { style: 'margin-top:12px' });

  const pick = (r) => {
    current = r.name;
    explainHost.replaceChildren(el('div.info-box', {}, r.explain));

    const f = frame();
    // The iframe navigates itself, so a slow build shows the browser's own loading state
    // rather than us faking one. src is a fixed literal path plus a validated name.
    f.src = `/api/reports/${encodeURIComponent(r.name)}/preview`;
    frameHost.replaceChildren(
      el('div.muted', { style: 'font-size:12.5px;margin-bottom:7px' },
        'Building this can take up to a minute the first time. It is then reused for 15 minutes.'),
      f);

    host.replaceChildren(buttons(pick), explainHost, frameHost);
  };

  host.replaceChildren(buttons(pick), explainHost, frameHost);
  return host;
}

/**
 * No reload is registered with the refresh control on purpose.
 *
 * Nothing on this page is stored: a report is built on demand, some sections fetch live NSE data
 * and one makes an AI call, and the server then reuses the result for 15 minutes. Re-mounting the
 * panel would only clear whichever report is open, and re-requesting it inside that window would
 * hand back the identical document. So the button here does what it can honestly do — re-read the
 * freshness strip — which is the fallback nav.js provides when a page registers nothing.
 */
async function boot() {
  await initChrome();

  mount(view, section('Your email reports, on screen',
    'The same reports the app emails you, viewable here without digging through your inbox. What you see is exactly what gets sent — identical content from identical code.',
    costNote('Each report is built on demand: some sections fetch fresh data from NSE and one uses an AI call, so a first build can take up to a minute. Nothing is emailed by opening a report here.'),
    paint()),
  section('Why these look like emails',
    'These reports were written for email, and their layout and the calculations inside them are the same piece of code. Showing the real thing is more trustworthy than rebuilding it and risking the two versions disagreeing — so they are displayed as-is rather than restyled to match the rest of the dashboard.',
    el('div', {})));
}

boot().catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load reports', String(err && err.message ? err.message : err))));
});
