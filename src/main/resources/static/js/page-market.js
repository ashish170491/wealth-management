/**
 * Market — institutional flows and the watchlist.
 *
 * /api/fiidii/summary serves IN-MEMORY state populated by a scheduled fetch, so it is
 * legitimately empty after the daily restart until 10:00. That is treated as a designed,
 * explained state here — never a spinner and never a blank panel, because "not fetched yet"
 * and "fetched and found nothing" mean completely different things to the reader.
 *
 * Deliberately NOT used (SPEC 27.4): /api/fiidii/report triggers a live NSE fetch and
 * /api/fiidii/debug-raw clears caches. Only the in-memory /summary is safe on page load.
 */

import { get, getList } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { inr, inrExact, pct, num, humanLabel, displaySymbol, stockHref, missing, sign, dateTimeIst, shortDate } from './format.js';
import { el, section, kpi, card, empty, skeleton, mount, badge, table, alert, unmeasured, scoreBar } from './ui.js';
import { barChart } from './charts.js';

const view = document.getElementById('view');

/** Explains a not-yet-run scan rather than showing an empty table. */
function notScannedYet(what, schedule) {
  return empty(`${what} has not run yet today`,
    `${schedule} The app restarts every weekday morning, which clears this until the next scan.`);
}

// ------------------------------------------------------------------ FII/DII

function fiiDiiPanel(s) {
  // The endpoint returns a bare {status: "No data available..."} before the 10:00 job.
  if (!s || (missing(s.fiiNet) && missing(s.diiNet))) {
    return notScannedYet('The institutional-flows report',
      'It is fetched at 9:45 AM and reported at 10:00 AM on weekdays.');
  }

  const cards = el('div.grid.kpis', {},
    kpi({ label: 'Foreign Investors (FII), Net', value: inr(s.fiiNet * 1e7), raw: s.fiiNet, tone: 'auto', sub: `${num(s.fiiNet, 0)} crore` }),
    kpi({ label: 'Indian Institutions (DII), Net', value: inr(s.diiNet * 1e7), raw: s.diiNet, tone: 'auto', sub: `${num(s.diiNet, 0)} crore` }),
    kpi({ label: 'Combined', value: inr((s.totalNet ?? 0) * 1e7), raw: s.totalNet, tone: 'auto' }),
    kpi({ label: 'Mood', value: humanLabel(s.sentiment), tone: 'neutral', sub: s.dataDate ? `for ${s.dataDate}` : undefined }));

  const flows = [];
  if (Number.isFinite(s.fiiNet)) flows.push({ label: 'FII today', value: s.fiiNet });
  if (Number.isFinite(s.diiNet)) flows.push({ label: 'DII today', value: s.diiNet });
  if (Number.isFinite(s.fii5DayNet)) flows.push({ label: 'FII last 5 days', value: s.fii5DayNet });
  if (Number.isFinite(s.dii5DayNet)) flows.push({ label: 'DII last 5 days', value: s.dii5DayNet });

  const alerts = (s.alerts || []).map((a) => alert({ severity: 'INFO', message: String(a) }));

  return el('div', {}, cards,
    flows.length ? card(barChart(flows, { width: 700, labelWidth: 150, format: (v) => `${num(v, 0)} cr` })) : null,
    alerts.length ? el('div', { style: 'margin-top:12px' }, alerts) : null);
}

// ------------------------------------------------------------------ watchlist

/** Teaser only — the full table, add/remove and the buy verdict live on watchlist.html (SPEC 37). */
function watchlistPanel(rows) {
  const link = el('p', {}, el('a', { href: 'watchlist.html' }, 'Open the full watchlist →'));
  if (!rows || rows.length === 0) {
    return el('div', {}, empty('Watchlist is empty', 'Nothing is being tracked as a potential buy right now.'), link);
  }
  const ORDER = { BUY_NOW: 0, ACCUMULATE: 1, WAIT_FOR_PULLBACK: 2, HOLD_OFF: 3, NOT_MEASURED: 4, AVOID: 5 };
  const top = [...rows].sort((a, b) => (ORDER[a.verdict] ?? 9) - (ORDER[b.verdict] ?? 9)).slice(0, 5);

  return el('div', {},
    table([
      { key: 'symbol', label: 'Stock', render: (w) => el('a', { href: stockHref(w.symbol) }, displaySymbol(w.symbol)) },
      { key: 'addedOn', label: 'Added', render: (w) => shortDate(w.addedOn) },
      { key: 'returnSinceAddPct', label: 'Since added', align: 'r', render: (w) => (missing(w.returnSinceAddPct)
        ? unmeasured('No price was recorded when this stock was added')
        : el('span', { class: sign(w.returnSinceAddPct) }, pct(w.returnSinceAddPct))) },
      { key: 'qualityScore', label: 'Quality', align: 'r', render: (w) => scoreBar(w.qualityScore) },
      { key: 'timingScore', label: 'Timing', align: 'r', render: (w) => scoreBar(w.timingScore) },
      { key: 'verdict', label: 'Still a good time to buy?', sortable: false, render: (w) => {
        const wrap = el('div', {}, badge(w.verdict));
        if (w.verdictReason) wrap.append(el('div.muted', { style: 'font-size:11px' }, w.verdictReason));
        return wrap;
      } },
    ], top, { sortKey: 'qualityScore' }),
    rows.length > top.length ? el('p.muted', {}, `Showing the top ${top.length} of ${rows.length} by verdict.`) : null,
    link);
}

// --------------------------------------------------------------------- boot

async function boot() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading market view…'), skeleton(4)));
  await initChrome();

  const [fii, watchlist] = await Promise.all([
    get('/api/fiidii/summary', { fallback: null }).then((r) => r.data).catch(() => null),
    // DB-only (watchlist + watchlist_daily_snapshot + latest score row), verified by hand.
    get('/api/watchlist/items', { fallback: { items: [] } }).then((r) => (r.data && r.data.items) || []).catch(() => []),
  ]);

  const nodes = [];

  nodes.push(section('Where the big money went',
    'FII means foreign institutional investors, DII means Indian ones (mutual funds, insurers). These are the largest buyers and sellers in the market, so sustained flows in one direction tend to move prices. Figures are in crore — one crore is ten million rupees.',
    fiiDiiPanel(fii)));


  nodes.push(section('Stocks on the watchlist',
    'Stocks you are tracking as possible buys, with the date you added each, how it has done since, and whether now is still a sensible time to buy. Quality is the business score from the screening; timing is the chart. The full list, with add and remove, is on the Watchlist page.',
    watchlistPanel(watchlist)));

  mount(view, nodes);
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load the market view', String(err && err.message ? err.message : err))));
});
