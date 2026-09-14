/**
 * Watchlist — stocks the investor is considering, with the date each was added, the return
 * since then (absolute and against the Nifty 50), sector, the two scores, the trend, and a
 * plain-English "still a good time to buy?" verdict (SPEC 37).
 *
 * Page load is ONE DB-only request (/api/watchlist/items — verified by hand, a page-load get()
 * is ungated, CLAUDE.md Gotcha 39). The three writes — add, refresh, remove — are button-only
 * and go through post(), which surfaces the server's 409/422 reason verbatim.
 *
 * Two scores are shown and never blended: Quality (is this a business worth owning for years?)
 * and Timing (is today a sensible day to pay this price?). A strong business at an extended
 * price reads "Wait for a dip", not "Buy".
 */

import { get, post } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters, sectorOptions } from './filters.js';
import {
  inrExact, pct, pp, humanLabel, displaySymbol, stockHref, missing, sign, shortDate, dateTimeIst, NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, empty, skeleton, mount, badge, table, alert, unmeasured, scoreBar, costNote,
} from './ui.js';
import { sparkline } from './charts.js';
import { entryPriceCell } from './buy-timing.js';
import { compoundingCell, compoundingRank } from './compounding.js';
import { macroExposureCol } from './macro-cells.js';

const view = document.getElementById('view');
const CRUNCH_START = '14:55';

let health = null;
let data = { count: 0, items: [] };
let busy = new Set();
let notice = null; // { severity, title, message }

// ------------------------------------------------------------------- helpers

/** True inside the 14:55–15:30 window when the server refuses Kite-calling writes (B-049). */
function inCrunch() {
  if (!health || !health.marketOpen) return false;
  const now = String(health.serverTimeIst || '').slice(11, 16);
  return now >= CRUNCH_START;
}

const VERDICT_ORDER = { BUY_NOW: 0, ACCUMULATE: 1, WAIT_FOR_PULLBACK: 2, HOLD_OFF: 3, NOT_MEASURED: 4, AVOID: 5 };

function verdictCell(w) {
  const wrap = el('div', { style: 'min-width:200px' });
  const b = badge(w.verdict, { type: w.verdict === 'NOT_MEASURED' ? 'unmeasured' : undefined });
  if (w.notMeasured && w.notMeasured.length) {
    b.title = `Not measured: ${w.notMeasured.join(', ')}`;
  }
  wrap.append(b);
  if (!w.qualityMeasured || !w.timingMeasured) {
    wrap.append(' ', el('span.muted', { style: 'font-size:11px' },
      !w.qualityMeasured && !w.timingMeasured ? '' : !w.qualityMeasured ? '(quality not measured)' : '(timing not measured)'));
  }
  if (w.verdictReason) wrap.append(el('div.muted', { style: 'font-size:12px;margin-top:3px' }, w.verdictReason));
  return wrap;
}

function returnCell(w) {
  if (missing(w.returnSinceAddPct)) {
    const why = w.source === 'SEED'
      ? 'No price was recorded when this stock was added (it came from the config file). Remove and re-add it to start measuring.'
      : 'No current price yet — press Refresh.';
    return unmeasured(why);
  }
  return el('span', { class: sign(w.returnSinceAddPct) }, pct(w.returnSinceAddPct));
}

function niftyCell(w) {
  if (missing(w.excessReturnPct)) {
    return unmeasured(missing(w.niftyAtAdd)
      ? 'Nifty level was not recorded when this stock was added'
      : 'No Nifty close recorded yet — the 3:00 PM run records one each day');
  }
  const node = el('span', { class: sign(w.excessReturnPct) }, pp(w.excessReturnPct));
  if (w.niftyAsOf) node.title = `Nifty ${pct(w.niftyReturnPct)} over the same period, as of ${shortDate(w.niftyAsOf)}`;
  return node;
}

function qualityCell(w) {
  if (missing(w.qualityScore)) {
    if (!missing(w.adhocQualityScore)) {
      const wrap = el('div', {}, scoreBar(w.adhocQualityScore));
      wrap.append(el('div.muted', { style: 'font-size:11px' }, `ad-hoc, ${shortDate(String(w.adhocQualityAt || ''))}`));
      return wrap;
    }
    return unmeasured(w.inUniverse
      ? 'Not screened yet — the daily 2:00 PM screening will score it'
      : 'Never screened: this stock is not in the screening universe, so it will not get a quality score');
  }
  const wrap = el('div', {}, scoreBar(w.qualityScore));
  const meta = el('div.muted', { style: 'font-size:11px' });
  if (w.qualityGrade) meta.append(`Grade ${w.qualityGrade}`);
  if (w.qualityAsOf) meta.append(w.qualityGrade ? ` · ${shortDate(w.qualityAsOf)}` : shortDate(w.qualityAsOf));
  wrap.append(meta);
  return wrap;
}

function timingCell(w) {
  if (missing(w.timingScore)) return unmeasured('Not analysed yet — press Refresh or wait for the 11:00 run');
  const wrap = el('div', {}, scoreBar(w.timingScore));
  const meta = el('div.muted', { style: 'font-size:11px' });
  if (w.entrySignal) meta.append(badge(w.entrySignal));
  if (!missing(w.rsi14)) meta.append(` RSI ${Number(w.rsi14).toFixed(0)}`);
  wrap.append(meta);
  return wrap;
}

function trendCell(w) {
  const wrap = el('div', {});
  if (w.trendDirection) wrap.append(badge(w.trendDirection));
  const pts = (w.series || []).map((p) => p.close);
  const spark = pts.length >= 2 ? sparkline(pts) : null;
  if (spark) {
    spark.title = `${pts.length} daily closes`;
    wrap.append(el('div', { style: 'margin-top:3px' }, spark));
  } else {
    wrap.append(el('div.muted', { style: 'font-size:11px;margin-top:3px' },
      `Building history — ${pts.length} of 90 days`));
  }
  return wrap;
}

/** Compact, sits right after the stock name so it never needs a horizontal scroll to reach. */
function actionsCell(w) {
  const wrap = el('div.actions-cell');
  const disabled = busy.has(w.symbol);
  const crunch = inCrunch();
  const refreshBtn = el('button.action.secondary.compact', {
    disabled: disabled || crunch,
    title: crunch ? 'Blocked until 3:30 PM — the 3:00–3:28 PM report jobs need the broker rate limit' : 'Refresh: re-analyse this stock now (about 2 seconds)',
    onclick: () => doRefresh(w.symbol, false),
  }, disabled ? '…' : '↻');
  const qualityBtn = el('button.action.secondary.compact', {
    disabled: disabled || crunch,
    title: crunch ? 'Blocked until 3:30 PM' : 'Refresh + quality: also compute an ad-hoc quality score (5–20 seconds, not a screening run)',
    onclick: () => doRefresh(w.symbol, true),
  }, '↻Q');
  const removeBtn = el('button.action.secondary.compact', {
    disabled,
    title: 'Remove from the list. Its history is kept and shown under "Removed".',
    onclick: () => doRemove(w.symbol),
  }, '✕');
  wrap.append(refreshBtn, qualityBtn, removeBtn);
  return wrap;
}

// ------------------------------------------------------------------- actions

async function reload() {
  const r = await get('/api/watchlist/items?includeRemoved=true', { fallback: { count: 0, items: [] } });
  data = r.data || { count: 0, items: [] };
  render();
}

async function doAdd(symbol, note) {
  notice = null;
  busy.add('__add');
  render();
  try {
    const r = await post('/api/watchlist/items', { symbol, note });
    const w = r.data;
    notice = {
      severity: 'OPPORTUNITY',
      title: `${displaySymbol(w.symbol)} added at ${inrExact(w.priceAtAdd, true)}`,
      message: w.inUniverse
        ? 'Timing was analysed just now. Its quality score arrives with the next 2:00 PM screening.'
        : 'Timing was analysed just now. This stock is NOT in the screening universe, so it will never get a quality score unless you add it to the universe (Discovery page) — the verdict will lean on timing alone.',
    };
  } catch (err) {
    notice = { severity: 'WARNING', title: 'Could not add', message: String(err && err.message ? err.message : err) };
  } finally {
    busy.delete('__add');
  }
  await reload();
}

async function doRefresh(symbol, quality) {
  notice = null;
  busy.add(symbol);
  render();
  try {
    await post(`/api/watchlist/items/refresh?symbol=${encodeURIComponent(symbol)}&quality=${quality}`, undefined, quality ? 90000 : 30000);
  } catch (err) {
    notice = { severity: 'WARNING', title: `Refresh of ${displaySymbol(symbol)} failed`, message: String(err && err.message ? err.message : err) };
  } finally {
    busy.delete(symbol);
  }
  await reload();
}

async function doRemove(symbol) {
  if (!window.confirm(`Remove ${displaySymbol(symbol)} from the watchlist? Its history is kept.`)) return;
  notice = null;
  busy.add(symbol);
  render();
  try {
    await post(`/api/watchlist/items/remove?symbol=${encodeURIComponent(symbol)}`);
  } catch (err) {
    notice = { severity: 'WARNING', title: `Could not remove ${displaySymbol(symbol)}`, message: String(err && err.message ? err.message : err) };
  } finally {
    busy.delete(symbol);
  }
  await reload();
}

// -------------------------------------------------------------------- render

function kpis(active) {
  const measured = active.filter((w) => !missing(w.returnSinceAddPct)).map((w) => w.returnSinceAddPct).sort((a, b) => a - b);
  const median = measured.length ? measured[Math.floor(measured.length / 2)] : null;
  const beat = active.filter((w) => !missing(w.excessReturnPct) && w.excessReturnPct > 0).length;
  const withNifty = active.filter((w) => !missing(w.excessReturnPct)).length;
  const count = (v) => active.filter((w) => w.verdict === v).length;

  return el('div.grid.kpis', {},
    kpi({ label: 'Tracked', value: String(active.length), sub: `${active.filter((w) => w.inHoldings).length} also in your portfolio` }),
    kpi({ label: 'Buy now', value: String(count('BUY_NOW')), sub: `${count('ACCUMULATE')} to accumulate`, tone: count('BUY_NOW') > 0 ? 'positive' : 'neutral' }),
    kpi({ label: 'Wait for a dip', value: String(count('WAIT_FOR_PULLBACK')), sub: `${count('HOLD_OFF')} hold off · ${count('AVOID')} avoid`, tone: 'neutral' }),
    kpi({ label: 'Median return since added', value: median === null ? NOT_MEASURED : pct(median), raw: median, tone: 'auto',
      sub: measured.length ? `across ${measured.length} with a recorded add price` : 'no stock has a recorded add price yet' }),
    kpi({ label: 'Beating the Nifty', value: withNifty ? `${beat} of ${withNifty}` : NOT_MEASURED,
      sub: withNifty ? 'since each was added' : 'needs a recorded Nifty level at add time', tone: 'neutral' }));
}

function addForm() {
  const crunch = inCrunch();
  const adding = busy.has('__add');
  const symbolInput = el('input.field', { type: 'text', placeholder: 'Symbol, e.g. RELIANCE or NSE:TATAPOWER', maxlength: 30, disabled: adding || crunch });
  const noteInput = el('input.field.wide', { type: 'text', placeholder: 'Why are you watching it? (optional, one line)', maxlength: 500, disabled: adding || crunch });
  const button = el('button.action', { disabled: adding || crunch, onclick: submit }, adding ? 'Adding…' : 'Add to watchlist');

  function submit() {
    const s = symbolInput.value.trim();
    if (!s) { symbolInput.focus(); return; }
    doAdd(s, noteInput.value.trim());
  }
  symbolInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  noteInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });

  const form = el('div.form-row', {}, symbolInput, noteInput, button);
  const note = crunch
    ? costNote(`Adding is paused between ${CRUNCH_START} and 3:30 PM: it prices the stock live on Kite, and the 3:00–3:28 PM report jobs need that rate limit. Try again after the close.`)
    : costNote('Adding prices the stock live and runs its technical analysis — about 3 seconds. The quality score arrives with the next 2:00 PM screening.');
  return el('div', {}, note, form);
}

/**
 * Watchlist chips (SPEC 27.13). The two questions this page keeps apart — is the business worth
 * owning, is today a sensible day to pay this price — get a group each, so the reader can ask
 * for one without the other. "Never screened" is its own chip rather than being lumped in with a
 * low score: a stock outside the screening universe has not been judged (Gotcha 21).
 */
const FILTERS = chipFilters([
  {
    label: 'Timing:',
    key: 'verdict',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'BUY_NOW', text: 'Buy now', test: (w) => w.verdict === 'BUY_NOW' },
      { value: 'ACCUMULATE', text: 'Accumulate', test: (w) => w.verdict === 'ACCUMULATE' },
      { value: 'WAIT', text: 'Wait for a dip', test: (w) => w.verdict === 'WAIT_FOR_PULLBACK' || w.verdict === 'HOLD_OFF' },
      { value: 'AVOID', text: 'Avoid', test: (w) => w.verdict === 'AVOID' },
      { value: 'NOT_MEASURED', text: 'Not measured', test: (w) => !w.verdict || w.verdict === 'NOT_MEASURED' },
    ],
  },
  {
    label: 'Quality:',
    key: 'quality',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'GOOD', text: 'Score 65+', test: (w) => !missing(w.qualityScore) && w.qualityScore >= 65,
      },
      {
        value: 'WEAK', text: 'Under 50', test: (w) => !missing(w.qualityScore) && w.qualityScore < 50,
      },
      {
        value: 'NEVER',
        text: 'Never screened',
        test: (w) => missing(w.qualityScore),
        title: 'Outside the screening universe, so the business has never been scored. Not a low score — no score.',
      },
    ],
  },
  {
    label: 'Since added:',
    key: 'move',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'UP', text: 'Up', test: (w) => !missing(w.returnSinceAddPct) && w.returnSinceAddPct > 0 },
      { value: 'DOWN', text: 'Down', test: (w) => !missing(w.returnSinceAddPct) && w.returnSinceAddPct < 0 },
      {
        value: 'BEAT',
        text: 'Beating the Nifty',
        test: (w) => !missing(w.excessReturnPct) && w.excessReturnPct > 0,
        title: 'Ahead of simply buying the index over the same days.',
      },
    ],
  },
  { label: 'Sector:', key: 'sector', ownLine: true, options: (rs) => sectorOptions(rs) },
  { search: true, placeholder: 'Search a stock…' },
], (opts) => render(opts));

function mainTable(active) {
  if (!active.length) {
    return empty('Nothing on the watchlist yet', 'Add a stock above. The date, price and Nifty level are recorded the moment you add it, so the return column starts measuring from day one.');
  }
  return table([
    { key: 'symbol', label: 'Stock', render: (w) => {
      const wrap = el('div', {}, el('a', { href: stockHref(w.symbol) }, displaySymbol(w.symbol)));
      if (w.inHoldings) wrap.append(' ', el('span.badge.info', { title: 'You already own this stock' }, 'owned'));
      if (w.source === 'SEED') wrap.append(' ', el('span.badge.neutral', { title: 'Seeded from application.yml — no add price recorded' }, 'config'));
      return wrap;
    } },
    { key: 'actions', label: 'Actions', sortable: false, render: actionsCell },
    { key: 'addedOn', label: 'Added', render: (w) => {
      const wrap = el('div', {}, shortDate(w.addedOn));
      if (!missing(w.daysWatched)) wrap.append(el('div.muted', { style: 'font-size:11px' }, `${w.daysWatched} day${w.daysWatched === 1 ? '' : 's'}`));
      return wrap;
    } },
    { key: 'sector', label: 'Sector', render: (w) => w.sector || unmeasured('No sector recorded — appears once the stock is screened') },
    { key: 'currentPrice', label: 'Price', align: 'r', render: (w) => {
      const wrap = el('div', {}, inrExact(w.currentPrice, true));
      if (!missing(w.dayChangePercent)) wrap.append(el('div', { class: sign(w.dayChangePercent), style: 'font-size:11px' }, pct(w.dayChangePercent)));
      return wrap;
    } },
    { key: 'returnSinceAddPct', label: 'Since added', align: 'r', render: returnCell },
    { key: 'excessReturnPct', label: 'vs Nifty', align: 'r', render: niftyCell },
    { key: 'qualityScore', label: 'Quality', align: 'r', render: qualityCell },
    { key: 'timingScore', label: 'Timing', align: 'r', render: timingCell },
    // Same lens, same renderer and same server-side rule table as the screener, the stock page
    // and the portfolio. Reusing compoundingCell is the point: four surfaces cannot disagree if
    // there is only one of them (SPEC 41.5).
    {
      key: 'compounding',
      label: 'Compounds?',
      value: compoundingRank,
      render: compoundingCell,
    },
    // Same renderer as the portfolio, the screener, discovery and the stock page — five
    // surfaces, one cell, nothing to drift (SPEC 48.10).
    macroExposureCol(),
    { key: 'trendDirection', label: 'Trend (90 days)', sortable: false, render: trendCell },
    { key: 'verdict', label: 'Still a good time to buy?', value: (w) => VERDICT_ORDER[w.verdict] ?? 9, render: verdictCell },
    // Same rule and same renderer as the screener, so one stock cannot show two entry levels.
    { key: 'suggestedEntryPrice', label: 'How to buy', align: 'r', value: (w) => w.suggestedEntryPrice, render: entryPriceCell },
    { key: 'addedNote', label: 'Your note', sortable: false, render: (w) => w.addedNote ? el('span', { style: 'font-size:12px' }, w.addedNote) : el('span.muted', {}, '—') },
  // filter:false — the search for these rows is in the chip bar above (Gotcha 85). The
  // removed-stocks table below keeps its own box: the chips do not govern it.
  ], active, { sortKey: 'verdict', sortDir: 'asc', filter: false });
}

function removedTable(removed) {
  if (!removed.length) return null;
  const details = el('details', { style: 'margin-top:12px' });
  details.append(el('summary', { style: 'cursor:pointer;font-weight:600' }, `Removed stocks (${removed.length}) — how did they do while you watched?`));
  details.append(table([
    { key: 'symbol', label: 'Stock', render: (w) => el('a', { href: stockHref(w.symbol) }, displaySymbol(w.symbol)) },
    { key: 'addedOn', label: 'Added', render: (w) => shortDate(w.addedOn) },
    { key: 'removedOn', label: 'Removed', render: (w) => shortDate(w.removedOn) },
    { key: 'daysWatched', label: 'Days watched', align: 'r', render: (w) => (missing(w.daysWatched) ? unmeasured() : String(w.daysWatched)) },
    { key: 'priceAtAdd', label: 'Price when added', align: 'r', render: (w) => inrExact(w.priceAtAdd, true) },
    { key: 'priceAtRemoval', label: 'Price when removed', align: 'r', render: (w) => inrExact(w.priceAtRemoval, true) },
    { key: 'returnWhileWatchedPct', label: 'Return while watched', align: 'r', render: (w) => (missing(w.returnWhileWatchedPct)
      ? unmeasured('Needs a recorded price at both add and removal')
      : el('span', { class: sign(w.returnWhileWatchedPct) }, pct(w.returnWhileWatchedPct))) },
    { key: 'addedNote', label: 'Your note', sortable: false, render: (w) => w.addedNote || el('span.muted', {}, '—') },
  ], removed, { sortKey: 'removedOn' }));
  return details;
}

function render(opts = {}) {
  const items = data.items || [];
  const active = items.filter((w) => w.active);
  const removed = items.filter((w) => !w.active);
  const nodes = [];

  if (notice) nodes.push(el('div.section', {}, alert(notice)));

  nodes.push(section('Your watchlist at a glance',
    'Stocks you are considering but do not yet own (or want to add to). Each one records the day you added it and the price and Nifty level that day, so "return since added" measures what you would have made — and whether waiting cost you anything against the index. Nothing here places orders.',
    kpis(active)));

  nodes.push(section('Add a stock',
    'Type the NSE symbol (Zerodha spelling). The app records today as the added date, prices it live, and runs its chart analysis straight away.',
    addForm()));

  const shown = FILTERS.apply(active);

  nodes.push(section('Still a good time to buy?',
    'Two separate questions, never blended: Quality asks whether this is a business worth owning for years (the 0-100 composite score from the daily screening — earnings, balance sheet, ownership). Timing asks whether today is a sensible day to pay this price (trend, RSI, distance from the 50-day average). "Buy now" needs both. "Accumulate" means the business is good but there is no entry trigger, so buy in small tranches. "Wait for a dip" means it has run — RSI is high, or it is stretched above its average. "Avoid" means a red flag on the books, a thinly traded stock, or a quality score under 50, and no chart fixes that. "Not measured" is exactly that: nothing has been analysed yet. Actions sit next to each name: ↻ re-analyses the stock now, ↻Q also computes an ad-hoc quality score, ✕ removes it (history kept).',
    FILTERS.bar(active, shown.length, 'stocks'),
    mainTable(shown),
    removedTable(removed)));

  mount(view, nodes);

  // Typing in the filter search re-paints the page, so the box must not lose the cursor on
  // every keystroke (the screener's paint does the same).
  if (opts.keepFocus) {
    const box = view.querySelector('input[type="search"]');
    if (box) {
      box.focus();
      box.setSelectionRange(box.value.length, box.value.length);
    }
  }
}

// ---------------------------------------------------------------------- boot

async function boot() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading watchlist…'), skeleton(3)));
  health = await initChrome();
  await reload();
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load the watchlist', String(err && err.message ? err.message : err))));
});
