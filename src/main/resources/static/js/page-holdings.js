/**
 * My Portfolio — the "what do I own and what should I do about it" screen.
 *
 * Split into Action Items and Analysis, mirroring the deliberate two-email split in
 * HoldingsReportService (see its sendDailyHoldingsReport). That split is an information-
 * architecture decision — "decide now" vs "read later" — and worth preserving here.
 *
 * Every page-load fetch below is DB-only. The click-only writes (thesis, dividend received,
 * tranche fill, plan cancel) are the SPEC 27.8 carve-out extended by SPEC 46.7: the page is where
 * the investor reads the state, so it is where they must be able to correct it. Nothing here
 * places an order.
 *
 * 2026-09-09 review (SPEC 46): the page could not say whether the money was growing — the
 * headline "+17.3%" read the same in February and September while seven months went by flat.
 * It now leads with a time-weighted return against two indices, total return in rupees,
 * drawdown, cash, weight-versus-quality, holding period, and which holdings are young listings.
 */

import { get, getList, post, postReadOnly, put, del } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters, sectorOptions } from './filters.js';
import {
  inr,
  inrExact,
  pct,
  pp,
  num,
  humanLabel,
  displaySymbol,
  stockHref,
  missing,
  sign,
  shortDate,
  NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, card, alert, empty, skeleton, mount, badge, table, scoreBar, unmeasured, costNote,
  withCount, withSummary,
  revealSection,
} from './ui.js';
import { donut, gauge, barChart, sparkline, lineChart, legend, scatter, COLORS } from './charts.js';
import { entryPriceCell } from './buy-timing.js';
import { compoundingCell, compoundingRank, COMPOUNDING } from './compounding.js';
import { macroExposureCol, macroFilterGroup, macroCoverageLine } from './macro-cells.js';
import { themeCol, themeFilterGroup, themeCoverageLine } from './theme-cells.js';
import {
  analystCoverageCol, analystFilterGroup, analystCoverageLine, analystCoveragePanel,
} from './analyst-cells.js';
import { resultCol, resultFilterGroup, resultCoverageLine } from './earnings-cells.js';

const view = document.getElementById('view');

/** Renders each tab lazily so switching does not refetch. */
const TABS = [
  { id: 'actions', label: 'Action Items' },
  { id: 'analysis', label: 'Analysis' },
  { id: 'trends', label: 'Trends' },
  { id: 'plan', label: 'Plan & Tax' },
];

let data = {};

/** Positions below this share of the book cannot move it either way (SPEC 46.3). */
const TOO_SMALL_PCT = 2;

// ------------------------------------------------------------------ helpers

function pnlCell(h) {
  const wrap = el('span', {});
  wrap.append(
    el('span', { class: sign(h.pnl) }, inr(h.pnl)),
    el('span.muted', { style: 'font-size:11.5px' }, ` ${pct(h.pnlPercent)}`));
  return wrap;
}

function symbolCell(h) {
  return el('a', { href: stockHref(h.symbol), title: h.symbol }, displaySymbol(h.symbol));
}

function totalValue(rows) {
  return (rows || []).reduce((s, h) => s + (h.currentValue || 0), 0);
}

function weightOf(h) {
  const t = totalValue(data.holdings);
  return t > 0 ? ((h.currentValue || 0) / t) * 100 : null;
}

function sectorOf(h) {
  return h.sector && h.sector !== 'UNKNOWN' ? humanLabel(h.sector) : null;
}

/** Days held, or the explicit unknown marker — never "0 days" for a missing lot (SPEC 46.5). */
function heldCell(h) {
  if (missing(h.daysHeld)) {
    return unmeasured(h.holdingPeriodSource === 'UNKNOWN' || !h.holdingPeriodSource
      ? 'No purchase lot on file for this holding — import the Zerodha tradebook to fill it'
      : 'Holding period could not be read');
  }
  const wrap = el('span', {});
  const years = h.daysHeld >= 365 ? `${(h.daysHeld / 365).toFixed(1)}y` : `${h.daysHeld}d`;
  wrap.append(el('span', { title: `Since ${shortDate(h.firstBuyDate)} (${humanLabel(h.holdingPeriodSource)})` }, years));
  if (h.stcgQuantity > 0 && h.ltcgEligibleQuantity > 0) {
    wrap.append(el('span.muted', { style: 'font-size:11px' }, ' mixed'));
  } else if (h.stcgQuantity > 0 && !missing(h.daysUntilNextLtcg)) {
    wrap.append(el('span.muted', { style: 'font-size:11px' }, ` · LTCG in ${h.daysUntilNextLtcg}d`));
  } else if (h.ltcgEligibleQuantity > 0) {
    wrap.append(el('span.muted', { style: 'font-size:11px' }, ' · long-term'));
  }
  return wrap;
}

// -------------------------------------------------------------- CORE TIER

const CORE_BADGE = {
  CORE: { type: 'success', label: 'Core' },
  CORE_WATCH: { type: 'warning', label: 'Core · watch' },
  SATELLITE: { type: 'info', label: 'Satellite' },
  UNCLASSIFIED: { type: 'unmeasured', label: NOT_MEASURED },
};

/** symbol -> classification row, from the latest classification date that has rows. */
function coreBySymbol() {
  const map = new Map();
  for (const v of (data.core && data.core.holdings) || []) map.set(v.symbol, v);
  return map;
}

function coreTierCell(view) {
  if (!view) return unmeasured('This holding has not been classified yet');
  const spec = CORE_BADGE[view.effectiveTier] || CORE_BADGE.UNCLASSIFIED;
  if (view.effectiveTier === 'UNCLASSIFIED') {
    return unmeasured((view.missingInputs && view.missingInputs[0]) || 'Not enough evidence to judge');
  }
  return badge(view.effectiveTier, spec);
}

/**
 * Durability, or the explicit not-measured marker. Never 0 and never blank — a withheld score
 * shown as a number is indistinguishable from a genuinely poor one.
 */
function durabilityCell(view) {
  if (!view || missing(view.durabilityScore)) {
    return unmeasured((view && view.durabilityCoverage)
      || 'Fewer than three of the five durability components could be measured');
  }
  const wrap = el('span', { title: view.durabilityCoverage || '' });
  wrap.append(scoreBar(view.durabilityScore));
  return wrap;
}

function coreSection() {
  const core = data.core;
  if (!core || !core.enabled) return null;

  const held = new Set((data.holdings || []).map((h) => h.symbol));
  const rows = ((core.holdings) || []).filter((v) => held.has(v.symbol));
  if (rows.length === 0) return null;

  const protectedRows = rows.filter((v) => v.effectiveTier === 'CORE' || v.effectiveTier === 'CORE_WATCH');
  const unclassified = rows.filter((v) => v.effectiveTier === 'UNCLASSIFIED');

  const body = [];

  if (core.mode === 'OBSERVATION') {
    body.push(alert({
      severity: 'INFO',
      title: 'Observation mode',
      message: 'Being a core holding currently changes only what the reports say, never which '
        + 'alerts are sent. Every exit alert still fires. The app is recording which of them land '
        + 'on core holdings so the decision to start withholding them can be made on evidence '
        + 'rather than on a hunch.',
    }));
  }

  if (protectedRows.length === 0) {
    body.push(empty('No core holdings yet',
      'No holding currently clears every quality gate on real evidence. That is a finding, not an '
      + 'error — see the not-measured list below for the ones that could not be judged.'));
  } else {
    body.push(table([
      { key: 'symbol', label: 'Stock', render: (v) => el('a', { href: stockHref(v.symbol), title: v.symbol }, displaySymbol(v.symbol)) },
      { key: 'effectiveTier', label: 'Tier', render: coreTierCell },
      { key: 'durabilityScore', label: 'Durability', align: 'r', render: durabilityCell },
      {
        key: 'strength',
        label: 'Key strength',
        sortable: false,
        value: () => '',
        render: (v) => {
          const pass = (v.gates || []).find((g) => g.status === 'PASS');
          return pass ? el('span.muted', {}, pass.reason) : unmeasured();
        },
      },
      {
        key: 'watch',
        label: 'Watch',
        sortable: false,
        value: () => '',
        render: (v) => {
          if (v.pendingChange) return el('span.muted', { style: 'color:#e65100' }, v.pendingChange);
          if (v.overrideApplied) return el('span.muted', {}, `your override: ${humanLabel(v.overrideApplied)}`);
          const soft = (v.softSignals || []).filter(Boolean);
          return soft.length ? el('span.muted', {}, soft.join('; ')) : el('span.muted', {}, '—');
        },
      },
    ], protectedRows, { sortKey: 'durabilityScore' }));
  }

  if (unclassified.length > 0) {
    body.push(card(
      el('div', {}, el('strong', {}, `Not enough data to judge (${unclassified.length})`)),
      el('ul', { style: 'margin:6px 0 0;padding-left:18px' }, ...unclassified.map((v) => el('li', {},
        el('strong', {}, displaySymbol(v.symbol)),
        el('span.muted', {}, ` — ${(v.missingInputs && v.missingInputs[0]) || 'evidence unavailable'}`)))),
      el('div.meta', {}, 'These are absent from the core list on purpose. A stock the app could '
        + 'not measure is never presented as one it judged and found wanting. The count falls as '
        + 'annual history is imported and as these stocks enter the weekly screening.')));
  }

  return withCount(section('Which stocks should you never sell?',
    'A core holding is one whose business has passed every quality check the app can run on it — '
    + 'how well it earns on the money invested in it, how solid its balance sheet is, how steady '
    + 'its earnings are, whether its accounts throw up red flags, and whether your original reason '
    + 'for buying still holds. Price is deliberately not one of the checks: a good business having '
    + 'a bad six months is the exact situation this is for. Nothing here is executed.',
    body), protectedRows.length);
}

// ------------------------------------------------------------ ACTION ITEMS

/** True inside the 14:55-15:30 window when the server refuses Kite-calling writes (B-049). */
function inCrunch() {
  const h = data.health;
  if (!h || !h.marketOpen) return false;
  return String(h.serverTimeIst || '').slice(11, 16) >= '14:55';
}

/**
 * Best-first, so sorting the column puts what to act on at the top. Mirrors page-watchlist.js
 * deliberately: one stock must read the same on every screen (Gotcha 81).
 */
const VERDICT_ORDER = { BUY_NOW: 0, ACCUMULATE: 1, WAIT_FOR_PULLBACK: 2, HOLD_OFF: 3, NOT_MEASURED: 4, AVOID: 5 };

function buyTimingFor(symbol) {
  return (data.buyTiming || {})[symbol] || null;
}

/**
 * The Signal column (SPEC 6.6).
 *
 * Shows the reconciled signal, not the raw stored one: the stored rule is a momentum rule that
 * cannot see fundamentals, so it called BEL a BUY while the column beside it said AVOID on a
 * forensic red flag (9 of 32 holdings disagreed). A quality problem vetoes a buy; the badge is
 * marked and the reason travels with it, so the downgrade is visible rather than silent.
 */
function signalCell(h) {
  const t = buyTimingFor(h.symbol);
  const shown = h.displaySignal || (t && t.displaySignal) || h.recommendation;
  const note = h.signalNote || (t && t.signalNote);
  if (!shown) return unmeasured('No signal computed for this holding yet');
  const node = badge(shown);
  if (note) {
    node.title = note;
    const wrap = el('div');
    wrap.append(node);
    wrap.append(el('div.muted', { style: 'font-size:11px;margin-top:2px' }, 'adjusted for quality'));
    wrap.title = note;
    return wrap;
  }
  return node;
}

/**
 * "Should I add more?" — the watchlist's verdict when the stock is also tracked, otherwise the
 * holding's own row through the same rule table. The source is labelled so the two are never
 * silently conflated.
 */
function buyTimingCell(h) {
  const t = buyTimingFor(h.symbol);
  if (!t || !t.verdict) return unmeasured('Not computed yet — refresh this row, or wait for the next holdings analysis');
  const wrap = el('div', { style: 'min-width:200px' });
  const b = badge(t.verdict, { type: t.verdict === 'NOT_MEASURED' ? 'unmeasured' : undefined });
  if (t.notMeasured && t.notMeasured.length) b.title = `Not measured: ${t.notMeasured.join(', ')}`;
  wrap.append(b);
  if (t.source === 'WATCHLIST') {
    wrap.append(' ', el('span.muted', { style: 'font-size:11px' }, '(from your watchlist)'));
  } else if (missing(t.qualityScore)) {
    wrap.append(' ', el('span.muted', { style: 'font-size:11px' }, '(quality not measured)'));
  }
  if (t.reason) wrap.append(el('div.muted', { style: 'font-size:12px;margin-top:3px' }, t.reason));
  if (t.qualityFrom && t.qualityFrom !== h.symbol) {
    wrap.append(el('div.muted', { style: 'font-size:11px;margin-top:2px' }, `Scored from ${displaySymbol(t.qualityFrom)}`));
  }
  return wrap;
}

/** Compact, and placed right after the stock name so it never needs a horizontal scroll. */
function holdingActionsCell(h) {
  const wrap = el('div.actions-cell');
  const disabled = busy.has(h.symbol);
  const crunch = inCrunch();
  wrap.append(el('button.action.secondary.compact', {
    disabled: disabled || crunch,
    title: crunch
      ? 'Blocked until 3:30 PM — the 3:05–3:28 PM report jobs need the shared broker rate limit'
      : 'Refresh: re-price this holding and recompute its technicals now (about 2 seconds)',
    onclick: () => doRefreshHolding(h.symbol),
  }, disabled ? '…' : '↻'));
  return wrap;
}

async function doRefreshHolding(symbol) {
  refreshNotice = null;
  busy.add(symbol);
  paint(currentTab());
  try {
    await post(`/api/trading/holdings/refresh-one?symbol=${encodeURIComponent(symbol)}`, undefined, 45000);
    const [holdings, buyTiming] = await Promise.all([
      getList('/api/trading/holdings').then((r) => r.data).catch(() => data.holdings),
      get('/api/trading/holdings/buy-timing', { fallback: {} }).then((r) => r.data).catch(() => data.buyTiming),
    ]);
    data = { ...data, holdings, buyTiming };
  } catch (err) {
    refreshNotice = {
      title: `Could not refresh ${displaySymbol(symbol)}`,
      message: String(err && err.message ? err.message : err),
    };
  } finally {
    busy.delete(symbol);
  }
  paint(currentTab());
}

function exitTable(rows) {
  if (!rows || rows.length === 0) {
    return el('div.alert.success', {},
      el('div.alert-title', {}, 'No exit signals'),
      el('div', {}, 'Nothing you own is currently flagged Sell or Strong Sell.'));
  }
  const coreMap = coreBySymbol();
  return table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'coreTier', label: 'Tier', sortable: false, render: (h) => coreTierCell(coreMap.get(h.symbol)) },
    { key: 'recommendation', label: 'Signal', render: signalCell },
    {
      key: 'buyTiming',
      label: 'Still good to buy?',
      value: (h) => VERDICT_ORDER[(buyTimingFor(h.symbol) || {}).verdict] ?? 9,
      render: buyTimingCell,
    },
    { key: 'pnl', label: 'Your Gain / Loss', align: 'r', value: (h) => h.pnlPercent, render: pnlCell },
    { key: 'daysHeld', label: 'Held', align: 'r', render: heldCell },
    { key: 'currentPrice', label: 'Price', align: 'r', render: (h) => inrExact(h.currentPrice, true) },
    { key: 'overallScore', label: 'Score', align: 'r', render: (h) => scoreBar(h.overallScore) },
    { key: 'analysisNotes', label: 'Why', sortable: false, render: (h) => h.analysisNotes || unmeasured('No note recorded') },
  ], rows, { sortKey: 'pnl', sortDir: 'asc' });
}

function decayTable(rows) {
  const live = (rows || []).filter((d) => !['INTACT', 'NO_DATA', 'STALE'].includes(d.verdict));
  if (live.length === 0) {
    return el('div.alert.success', {},
      el('div.alert-title', {}, 'No thesis drift detected'),
      el('div', {}, 'Every holding the app can score still scores about as well as it did a month ago.'));
  }
  return table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'verdict', label: 'Verdict', render: (d) => badge(d.verdict) },
    { key: 'currentScore', label: 'Score Now', align: 'r', render: (d) => (missing(d.currentScore) ? unmeasured() : num(d.currentScore)) },
    { key: 'delta30d', label: '30-Day Change', align: 'r', render: (d) => (missing(d.delta30d) ? unmeasured('No score 30 days ago') : el('span', { class: sign(d.delta30d) }, num(d.delta30d))) },
    { key: 'relativeDelta30d', label: 'vs Peers 30d', align: 'r', render: (d) => (missing(d.relativeDelta30d) ? unmeasured('Universe shift not measured (too few stocks screened on both dates)') : el('span', { class: sign(d.relativeDelta30d) }, num(d.relativeDelta30d))) },
    { key: 'delta60d', label: '60-Day Change', align: 'r', render: (d) => (missing(d.delta60d) ? unmeasured('No score 60 days ago') : el('span', { class: sign(d.delta60d) }, num(d.delta60d))) },
    { key: 'pnlPercent', label: 'Your Return', align: 'r', render: (d) => (missing(d.pnlPercent) ? unmeasured() : el('span', { class: sign(d.pnlPercent) }, pct(d.pnlPercent))) },
    { key: 'reason', label: 'Detail', sortable: false },
  ], live, { sortKey: 'relativeDelta30d', sortDir: 'asc' });
}

function accumulateTable(rows) {
  if (!rows || rows.length === 0) {
    return empty('No buy signals among your holdings', 'None of your current stocks are flagged Buy or Strong Buy right now.');
  }
  return table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'recommendation', label: 'Signal', render: signalCell },
    { key: 'currentPrice', label: 'Price', align: 'r', render: (h) => inrExact(h.currentPrice, true) },
    { key: 'suggestedEntry', label: 'How to buy', align: 'r', value: (h) => (h.suggestedEntryRungs || [])[0]?.price, render: entryPriceCell },
    { key: 'overallScore', label: 'Score', align: 'r', render: (h) => scoreBar(h.overallScore) },
    { key: 'pnl', label: 'Your Gain / Loss', align: 'r', render: pnlCell },
  ], rows, { sortKey: 'overallScore' });
}

/**
 * Holdings that are listings under three years old (SPEC 46.6). The IPO tracker already knew
 * their stage and lock-in calendar; the portfolio page never asked. Stage is descriptive — the
 * supply calendar is the point: it says when the next tranche of sellers is free.
 */
const IPO_STAGE_BADGE = {
  HYPE_WINDOW: { type: 'warning', label: 'Hype window' },
  WASHOUT: { type: 'warning', label: 'Washout' },
  RECOVERING: { type: 'info', label: 'Recovering' },
  BASE_FORMING: { type: 'success', label: 'Base forming' },
  NOT_MEASURED: { type: 'unmeasured', label: NOT_MEASURED },
};

function youngListingsSection() {
  const rows = (data.holdings || []).filter((h) => h.ipoListingDate);
  if (rows.length === 0) return null;
  const share = rows.reduce((s, h) => s + (weightOf(h) || 0), 0);

  const tbl = table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'weight', label: 'Of your money', align: 'r', value: weightOf, render: (h) => (missing(weightOf(h)) ? unmeasured() : pct(weightOf(h), { signed: false })) },
    { key: 'ipoListingDate', label: 'Listed', render: (h) => shortDate(h.ipoListingDate) },
    {
      key: 'ipoStage',
      label: 'Stage',
      render: (h) => {
        const spec = IPO_STAGE_BADGE[h.ipoStage] || IPO_STAGE_BADGE.NOT_MEASURED;
        const b = badge(h.ipoStage || 'NOT_MEASURED', spec);
        b.title = h.ipoStageReason || '';
        return b;
      },
    },
    {
      key: 'vsIssue',
      label: 'vs issue price',
      align: 'r',
      value: (h) => (h.ipoIssuePrice > 0 && h.currentPrice > 0 ? ((h.currentPrice - h.ipoIssuePrice) / h.ipoIssuePrice) * 100 : null),
      render: (h) => {
        if (!(h.ipoIssuePrice > 0 && h.currentPrice > 0)) return unmeasured('Issue price not on record');
        const v = ((h.currentPrice - h.ipoIssuePrice) / h.ipoIssuePrice) * 100;
        return el('span', { class: sign(v), title: `Issue price ${inrExact(h.ipoIssuePrice, true)}` }, pct(v));
      },
    },
    {
      key: 'ipoNextUnlockDate',
      label: 'Next unlock',
      render: (h) => (h.ipoNextUnlockDate
        ? el('span', { title: h.ipoNextUnlockLabel || '' }, `${shortDate(h.ipoNextUnlockDate)} — ${h.ipoNextUnlockLabel || ''}`)
        : el('span.muted', {}, 'All lock-ins have expired')),
    },
    { key: 'pnl', label: 'Your Gain / Loss', align: 'r', value: (h) => h.pnlPercent, render: pnlCell },
  ], rows, { sortKey: 'weight' });

  return withCount(section('Recently listed holdings and their supply calendar',
    `${rows.length} of your holdings listed within the last three years, together ${pct(share, { signed: false })} of your money. `
    + 'A new listing has a queue of sellers on a fixed timetable — anchor investors at 30 and 90 days, '
    + 'pre-IPO holders at six months, promoters at 18 months. "Washout" means the stock is still below '
    + 'its listing-day high after the six-month wave; "base forming" means it has recovered and is holding. '
    + 'Nothing here is a verdict on the business — that is the quality column on the Analysis tab.',
    tbl), rows.length);
}

function renderActions() {
  const nodes = [];

  const core = coreSection();
  if (core) nodes.push(core);

  nodes.push(withCount(section('Stocks flagged to exit',
    'Holdings the app currently rates Sell or Strong Sell. This is a prompt to review your reasoning, not an instruction to sell — and it ignores tax, so check the holding period before acting.',
    exitTable(data.exitCandidates)), (data.exitCandidates || []).length));

  nodes.push(withCount(section('Is your reason for buying still true?',
    'Thesis drift compares each holding’s score today against its score 30 and 60 days ago. A falling score often shows up before the price does, which is exactly why it is worth watching. Holdings the app cannot score are left out rather than shown as zero.',
    // Holdings whose thesis is NOT intact — the list this section exists to surface. A count
    // of every holding would be a number describing a different list from the one below it.
    decayTable(data.decay)), (data.decay || []).filter((d) => d.verdict && d.verdict !== 'INTACT').length));

  const young = youngListingsSection();
  if (young) nodes.push(young);

  nodes.push(withCount(section('Holdings the app would add to',
    'Stocks you already own that currently rate Buy or Strong Buy. Worth a look if you were planning to invest more anyway.',
    accumulateTable(data.accumulateCandidates)), (data.accumulateCandidates || []).length));

  return nodes;
}

// --------------------------------------------------------------- ANALYSIS

/**
 * "Is your money actually growing?" (SPEC 46.1). Time-weighted, against two indices, with the
 * method and its caveats printed beside the numbers. A missing benchmark or a short history
 * reads "not measured", never 0%.
 */
function performanceSection() {
  const p = data.performance;
  if (!p) {
    // No chip value on purpose: with no snapshots there is no return to report, and the
    // heading says "not measured" rather than implying a flat one.
    return withSummary(section('Is your money actually growing?',
      'The headline gain compares today’s value with what you paid, and it cannot move when you add or withdraw money. This section removes those flows so the return can be compared with an index.',
      empty('Performance unavailable', 'Could not read the daily snapshots.')), null);
  }

  const tone = (v) => (missing(v) ? 'neutral' : v > 0 ? 'positive' : v < 0 ? 'negative' : 'neutral');
  const n50 = (p.benchmarks || []).find((b) => b.symbol === 'NSE:NIFTY 50') || {};
  const nmid = (p.benchmarks || []).find((b) => b.symbol === 'NSE:NIFTY MIDCAP 150') || {};

  const kpis = el('div.grid.kpis', {},
    kpi({
      label: `Your return, ${p.daysSpanned || 0} days`,
      value: missing(p.twrPercent) ? NOT_MEASURED : pct(p.twrPercent),
      sub: missing(p.twrAnnualisedPercent) ? 'time-weighted · too short to annualise' : `${pct(p.twrAnnualisedPercent)} a year · time-weighted`,
      tone: tone(p.twrPercent),
    }),
    kpi({
      label: 'Nifty 50, same period',
      value: missing(n50.returnPercent) ? NOT_MEASURED : pct(n50.returnPercent),
      sub: missing(p.excessVsNifty50Pp) ? (n50.coverage || 'no index data stored yet') : `you are ${pp(p.excessVsNifty50Pp)} ${p.excessVsNifty50Pp >= 0 ? 'ahead' : 'behind'}`,
      tone: tone(p.excessVsNifty50Pp),
    }),
    kpi({
      label: 'Nifty Midcap 150, same period',
      value: missing(nmid.returnPercent) ? NOT_MEASURED : pct(nmid.returnPercent),
      sub: missing(p.excessVsMidcap150Pp) ? (nmid.coverage || 'no index data stored yet') : `you are ${pp(p.excessVsMidcap150Pp)} ${p.excessVsMidcap150Pp >= 0 ? 'ahead' : 'behind'}`,
      tone: tone(p.excessVsMidcap150Pp),
    }),
    kpi({
      label: 'Headline gain on cost',
      value: missing(p.simpleGainPercent) ? NOT_MEASURED : pct(p.simpleGainPercent),
      sub: 'unrealised, today — does not move with deposits',
      tone: 'neutral',
    }));

  const dd = p.drawdown;
  const ddRow = el('div.grid.kpis', { style: 'margin-top:10px' },
    kpi({
      label: 'Deepest fall from a peak',
      value: !dd || missing(dd.maxDrawdownPercent) ? NOT_MEASURED : pct(dd.maxDrawdownPercent),
      sub: dd && dd.peakDate ? `peak ${shortDate(dd.peakDate)}${dd.maxDrawdownTrough ? ` to ${shortDate(dd.maxDrawdownTrough)}` : ''}` : 'needs daily history',
      tone: dd && dd.maxDrawdownPercent < -10 ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Below your peak now',
      value: !dd || missing(dd.currentDrawdownPercent) ? NOT_MEASURED : (dd.currentDrawdownPercent === 0 ? 'At a high' : pct(dd.currentDrawdownPercent)),
      sub: 'measured with deposits and withdrawals removed',
      tone: dd && dd.currentDrawdownPercent < -10 ? 'warning' : 'neutral',
    }),
    cashTile(p.cash, p),
  );

  // Chart: the three rebased lines on the union of dates.
  const pts = p.indexed || [];
  let chartNode = null;
  if (pts.length >= 2) {
    const series = [
      { name: 'Your portfolio', color: COLORS.navyLight, points: pts.map((x) => ({ x: x.d, y: x.portfolio })), format: (v) => `${v.toFixed(1)}` },
      { name: 'Nifty 50', color: COLORS.faint, points: pts.map((x) => ({ x: x.d, y: x.nifty50 ?? null })), format: (v) => `${v.toFixed(1)}` },
      { name: 'Nifty Midcap 150', color: COLORS.warn, points: pts.map((x) => ({ x: x.d, y: x.midcap150 ?? null })), format: (v) => `${v.toFixed(1)}` },
    ];
    const chart = lineChart(series, { height: 260, maxGapDays: 8, formatLeft: (v) => String(Math.round(v)) });
    chartNode = chart ? card(
      el('div.row.between.wrap', {},
        el('div.muted', { style: 'font-size:12.5px' }, `Every line starts at 100 on ${shortDate(p.from)}, so they can be read side by side`),
        legend(series)),
      chart) : null;
  }

  const caveats = (p.caveats || []).length
    ? el('ul', { style: 'margin:8px 0 0;padding-left:18px;font-size:12.5px' }, ...p.caveats.map((c) => el('li.muted', {}, c)))
    : null;

  const method = el('div', {
    style: 'padding:11px 13px;background:var(--navy-tint);border-left:3px solid var(--navy);border-radius:var(--radius);font-size:12.5px;margin-top:10px',
  },
  el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'How this is measured'),
  el('div', {}, p.method || ''),
  caveats);

  return withSummary(section('Is your money actually growing?',
    'The headline gain compares today’s value with what you paid, and it cannot move when you add or withdraw money — it read +17% in February and +17% in September while seven months went by. '
    + 'The return below removes your deposits and withdrawals day by day, so it measures what the holdings did and can be set against an index. Beating the index is the whole point of picking stocks yourself.',
    kpis, ddRow, chartNode, totalReturnCard(p.totalReturn, p.lotCoverage), method),
  // Time-weighted, not gain on cost — the distinction this whole section exists to draw. Gain
  // on cost read +17% in February and +17% in September while the money went nowhere.
  missing(p.twrPercent) ? null : pct(p.twrPercent),
  { type: p.twrPercent >= 0 ? 'success' : 'danger' });
}

function cashTile(cash, p) {
  if (!cash || missing(cash.availableCash)) {
    return kpi({ label: 'Cash available at the broker', value: NOT_MEASURED, sub: (cash && cash.note) || 'captured at the 15:00 snapshot', tone: 'neutral' });
  }
  return kpi({
    label: `Cash at the broker (as of ${shortDate(cash.asOf)})`,
    value: inr(cash.availableCash),
    sub: missing(cash.cashPercentOfTotal) ? 'ready to deploy' : `${pct(cash.cashPercentOfTotal, { signed: false })} of holdings plus cash`,
    tone: 'neutral',
  });
}

/** Unrealised + realised + dividends, each with what it rests on (SPEC 46.1). */
function totalReturnCard(t, lots) {
  if (!t) return null;
  const row = (label, value, note) => el('div.row.between', { style: 'font-size:13.5px;padding:4px 0;border-bottom:1px solid var(--rule)' },
    el('span', {}, label, note ? el('span.muted', { style: 'font-size:11.5px' }, ` ${note}`) : null),
    missing(value) ? unmeasured() : el('span.num', { class: sign(value) }, inr(value)));
  const lotNote = lots ? `(lots on file for ${lots.holdingsWithLots} of ${lots.holdings} holdings)` : '';
  return card(
    el('div', { style: 'font-weight:600;margin-bottom:6px' }, 'Total return in rupees, this financial year'),
    row('Unrealised gain on what you hold', t.unrealisedGain, ''),
    row('Realised short-term gains', t.realisedStcgFy, lotNote),
    row('Realised long-term gains', t.realisedLtcgFy, lotNote),
    row('Dividends received', t.dividendsReceivedFy, t.dividendEventsLogged === 0 ? '(none logged — see Plan & Tax)' : `(${t.dividendEventsLogged} logged)`),
    el('div.row.between', { style: 'font-size:14.5px;font-weight:600;padding:7px 0 0' },
      el('span', {}, 'Total'),
      missing(t.totalReturn) ? unmeasured() : el('span.num', { class: sign(t.totalReturn) }, inr(t.totalReturn))),
    el('div.meta', { style: 'margin-top:6px' }, t.note || ''));
}

/**
 * Weight versus quality (SPEC 46.3): the chart the review found missing. The three biggest
 * positions were 29% of the money in businesses the app rated partial, not-a-compounder and
 * never-screened, while its own compounders sat at 5%.
 */
function weightVsQualitySection() {
  const rows = (data.holdings || []).filter((h) => (h.currentValue || 0) > 0);
  if (rows.length === 0) return null;
  const colorFor = (h) => ({
    COMPOUNDER: COLORS.profit, PARTIAL: COLORS.warn, NO: COLORS.loss,
  })[h.compounding] || COLORS.faint;

  const scored = rows.filter((h) => Number.isFinite(h.overallScore));
  const points = scored.map((h) => ({
    x: weightOf(h),
    y: h.overallScore,
    label: displaySymbol(h.symbol),
    color: colorFor(h),
    note: `${displaySymbol(h.symbol)}: ${pct(weightOf(h), { signed: false })} of your money, score ${h.overallScore}, ${h.compounding ? COMPOUNDING[h.compounding]?.label || humanLabel(h.compounding) : 'never screened'}`,
  }));
  const chart = scatter(points, {
    xLabel: 'Share of your money (%)',
    yLabel: 'App score (0-100)',
    xRef: TOO_SMALL_PCT,
    yRef: 65,
    formatX: (v) => `${Math.round(v)}%`,
    yMin: 0,
    yMax: 100,
  });

  const legendRow = el('div.row.wrap', { style: 'gap:14px;font-size:12px;margin-top:6px' },
    ...[['Compounder', COLORS.profit], ['Partly', COLORS.warn], ['Does not compound', COLORS.loss], ['Never screened', COLORS.faint]]
      .map(([label, color]) => el('span', {}, el('span', { style: `display:inline-block;width:10px;height:10px;border-radius:50%;background:${color};margin-right:5px` }), label)),
    el('span.muted', {}, `Dashed lines: ${TOO_SMALL_PCT}% of your money, and the 65-point bar the app uses for a pick.`));

  const top = [...rows].sort((a, b) => (b.currentValue || 0) - (a.currentValue || 0)).slice(0, 8);
  const tbl = table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'weight', label: 'Of your money', align: 'r', value: weightOf, render: (h) => pct(weightOf(h), { signed: false }) },
    { key: 'overallScore', label: 'Score', align: 'r', render: (h) => scoreBar(h.overallScore) },
    { key: 'compounding', label: 'Compounds?', value: compoundingRank, render: compoundingCell },
    // Beside the compounding gate on purpose: one answers "is this a good business", the other
    // "is the weather against it just now". Adjacent, never blended — a headwind says nothing
    // about quality, and a quality verdict says nothing about the weather (SPEC 48.10).
    macroExposureCol(),
    themeCol(),
    { key: 'recommendation', label: 'Signal', render: signalCell },
    { key: 'pnl', label: 'Gain / Loss', align: 'r', value: (h) => h.pnl, render: pnlCell },
  ], top, { sortKey: 'weight' });

  const mismatches = top.filter((h) => h.compounding === 'NO' || (h.compounding === 'NOT_MEASURED' && Number.isFinite(h.overallScore) && h.overallScore < 50) || (Number.isFinite(h.overallScore) && h.overallScore < 50));
  const compoundersSmall = rows.filter((h) => h.compounding === 'COMPOUNDER' && (weightOf(h) || 0) < 5);
  const notes = [];
  if (mismatches.length) {
    notes.push(el('div', { style: 'margin-bottom:6px' },
      el('strong', {}, 'Big positions the app does not rate: '),
      mismatches.map((h) => `${displaySymbol(h.symbol)} (${pct(weightOf(h), { signed: false })})`).join(', '),
      '. Size should follow conviction and quality. A large position in a business the app cannot vouch for is a bet on something the app is not measuring — which can be fine, if you know what it is.'));
  }
  if (compoundersSmall.length) {
    notes.push(el('div', {},
      el('strong', {}, 'Compounders you own only a little of: '),
      compoundersSmall.map((h) => `${displaySymbol(h.symbol)} (${pct(weightOf(h), { signed: false })})`).join(', '),
      '. These are the ones where adding on weakness has historically paid.'));
  }

  return withCount(section('Are your biggest bets your best businesses?',
    'Each dot is a holding: how much of your money is in it against how the app scores it, coloured by whether the business looks like it can compound. Top-right is where you want your money. Bottom-right — big positions in weak businesses — is where portfolios get hurt.',
    card(chart || empty('Nothing to chart', 'No scored holdings yet.'), legendRow),
    el('div', { style: 'margin-top:12px' }, tbl),
    notes.length ? el('div', { style: 'font-size:13.5px;line-height:1.6;margin-top:10px' }, ...notes) : null),
    // The dots actually plotted. A holding the app has never scored has no y value and is not
    // on the chart, so counting every holding would describe a different list (Gotcha 98).
    scored.length);
}

/** Portfolio-weighted fundamentals with coverage (SPEC 46.3). */
function portfolioQualitySection() {
  const q = data.quality;
  if (!q || !q.metrics || q.metrics.length === 0) return null;
  const tiles = q.metrics.map((m) => kpi({
    label: m.label,
    value: missing(m.value) ? NOT_MEASURED : `${num(m.value, 1)}${m.unit === 'x' ? 'x' : m.unit === '%' ? '%' : ` ${m.unit}`}`,
    sub: `measured on ${pct(m.coveragePercentOfValue, { signed: false })} of your money (${m.holdingsMeasured} of ${m.holdingsTotal})`,
    tone: 'neutral',
  }));
  const notes = el('ul', { style: 'margin:10px 0 0;padding-left:18px;font-size:12.5px' },
    ...q.metrics.map((m) => el('li.muted', {}, el('strong', {}, `${m.label}: `), m.note || '')));
  return withCount(section('What does the whole book cost, and how well does it earn?',
    `The same numbers you would read for one stock, weighted across everything you own by what each holding is worth. Screening data as of ${q.screeningDate ? shortDate(q.screeningDate) : NOT_MEASURED}. A figure measured on under half your money is withheld rather than shown as if it covered all of it.`,
    el('div.grid.kpis', {}, ...tiles), notes),
    // Metrics that survived the coverage gate. One withheld for thin coverage is not on screen,
    // so it must not be in the count either.
    tiles.length);
}

/**
 * Portfolio chips (SPEC 27.13). Deliberately built from what the app already decided about each
 * holding — tier, the reconciled signal, the compounding gate, red flags — rather than any new
 * judgement (SPEC 20 rule 10). "Nobody looked" is its own chip beside "clean", because an empty
 * flag list is usually "nothing was checked" (Gotcha 44), and on a portfolio that distinction is
 * the difference between reassurance and a blind spot.
 */
const tierOf = (h) => (coreBySymbol().get(h.symbol) || {}).effectiveTier;

const HOLDING_FILTERS = chipFilters([
  {
    label: 'Tier:',
    key: 'tier',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'CORE', text: 'Core', test: (h) => tierOf(h) === 'CORE' },
      { value: 'SATELLITE', text: 'Satellite', test: (h) => tierOf(h) === 'SATELLITE' },
      {
        value: 'UNCLASSIFIED',
        text: 'Not classified',
        // UNCLASSIFIED is never SATELLITE (Gotcha 68): one is the absence of a finding, the
        // other is a finding. It gets its own chip rather than being swept into either.
        test: (h) => { const t = tierOf(h); return !t || t === 'UNCLASSIFIED'; },
        title: 'The seven core gates could not be run, or not enough of them were measured. Not a downgrade — no verdict.',
      },
    ],
  },
  {
    label: 'Signal:',
    key: 'signal',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'BUY', text: 'Buy / add', test: (h) => String(h.displaySignal || h.recommendation || '').includes('BUY') },
      { value: 'HOLD', text: 'Hold', test: (h) => String(h.displaySignal || h.recommendation || '') === 'HOLD' },
      { value: 'SELL', text: 'Sell', test: (h) => String(h.displaySignal || h.recommendation || '').includes('SELL') },
    ],
  },
  {
    label: 'Compounding:',
    key: 'compounding',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'COMPOUNDER', text: 'Compounders', test: (h) => h.compounding === 'COMPOUNDER' },
      { value: 'NO', text: 'Not compounding', test: (h) => h.compounding === 'NO' },
      {
        value: 'NOT_MEASURED', text: 'Could not judge',
        test: (h) => !h.compounding || h.compounding === 'NOT_MEASURED',
        title: 'The accounts could not be read well enough to run the five checks. Not a failure — an absence.',
      },
    ],
  },
  macroFilterGroup(),
  themeFilterGroup,
  analystFilterGroup(),
  resultFilterGroup(),
  {
    label: 'Position:',
    key: 'pnl',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'UP', text: 'In profit', test: (h) => h.pnlPercent > 0 },
      { value: 'DOWN', text: 'At a loss', test: (h) => h.pnlPercent < 0 },
    ],
  },
  { label: 'Sector:', key: 'sector', ownLine: true, options: (rs) => sectorOptions(rs) },
  { search: true, placeholder: 'Search a holding…' },
], (opts) => paint(currentTab(), opts));

function holdingsTable(rows, matrix) {
  if (!rows || rows.length === 0) {
    return empty('No holdings found', 'Either nothing is held, or holdings have not synced from your broker yet.');
  }

  return table([
    { key: 'symbol', label: 'Stock', render: symbolCell },
    { key: 'actions', label: '', sortable: false, render: holdingActionsCell },
    {
      key: 'buyTiming',
      label: 'Still good to buy?',
      value: (h) => VERDICT_ORDER[(buyTimingFor(h.symbol) || {}).verdict] ?? 9,
      render: buyTimingCell,
    },
    { key: 'weight', label: 'Of your money', align: 'r', value: weightOf, render: (h) => (missing(weightOf(h)) ? unmeasured() : pct(weightOf(h), { signed: false })) },
    { key: 'quantity', label: 'Qty', align: 'r' },
    { key: 'averagePrice', label: 'Avg Cost', align: 'r', render: (h) => inrExact(h.averagePrice, true) },
    { key: 'currentPrice', label: 'Price', align: 'r', render: (h) => inrExact(h.currentPrice, true) },
    { key: 'currentValue', label: 'Value', align: 'r', render: (h) => inr(h.currentValue) },
    { key: 'pnl', label: 'Gain / Loss', align: 'r', render: pnlCell },
    { key: 'daysHeld', label: 'Held', align: 'r', render: heldCell },
    {
      key: 'trend',
      label: '90-Day Trend',
      sortable: false,
      render: (h) => {
        const series = (matrix || {})[h.symbol];
        const spark = series ? sparkline(series.map((p) => p.close)) : null;
        return spark || unmeasured('Not enough daily snapshots yet');
      },
    },
    { key: 'overallScore', label: 'Score', align: 'r', render: (h) => scoreBar(h.overallScore) },
    { key: 'compounding', label: 'Compounds?', value: compoundingRank, render: compoundingCell },
    // Who else is watching this stock (SPEC 49.14). Beside the app's own verdict, never blended
    // into it: a brokerage target is somebody else's opinion recorded so it can be scored later,
    // and it contributes zero points to anything here.
    analystCoverageCol(),
    // What the business actually did last quarter (SPEC 50). Beside the app's own score and
    // never blended into it: the composite is 59% price behaviour (SPEC 40.2), so this is the
    // column that can disagree with it — which is the whole reason it is here.
    resultCol(),
    { key: 'recommendation', label: 'Signal', render: signalCell },
    { key: 'rsi14', label: 'RSI', align: 'r', render: (h) => (missing(h.rsi14) ? unmeasured() : h.rsi14.toFixed(0)) },
    { key: 'stockPe', label: 'P/E', align: 'r', render: (h) => (missing(h.stockPe) ? unmeasured('No P/E computed for this stock') : h.stockPe.toFixed(1)) },
    { key: 'sector', label: 'Sector', value: (h) => sectorOf(h) || '', render: (h) => sectorOf(h) || unmeasured('No sector recorded for this holding') },
    // Deliberately last (SPEC 46): a one-day move is the least useful number on a 5-10 year screen.
    { key: 'dayChangePercent', label: 'Today', align: 'r', render: (h) => (missing(h.dayChangePercent) ? unmeasured() : el('span', { class: sign(h.dayChangePercent) }, pct(h.dayChangePercent))) },
  ], rows, { sortKey: 'currentValue', filter: false });
}

/**
 * Portfolio-level read of the compounding lens (SPEC 41).
 */
function compoundingSummary(holdings) {
  const rows = holdings || [];
  if (rows.length === 0) return null;

  const counts = { COMPOUNDER: [], PARTIAL: [], NO: [], NOT_MEASURED: [] };
  for (const h of rows) {
    const v = h.compounding && counts[h.compounding] ? h.compounding : 'NOT_MEASURED';
    counts[v].push(h);
  }

  const value = (list) => list.reduce((sum, h) => sum + (h.currentValue || 0), 0);
  const invested = value(rows);
  const share = (list) => (invested > 0 ? (value(list) / invested) * 100 : null);

  const tile = (verdict, label, sub, tone) => kpi({
    label,
    value: String(counts[verdict].length),
    sub: missing(share(counts[verdict])) ? sub : `${pct(share(counts[verdict]))} of your money · ${sub}`,
    tone,
  });

  const names = (verdict, limit = 6) => counts[verdict]
    .sort((a, b) => (b.currentValue || 0) - (a.currentValue || 0))
    .slice(0, limit)
    .map((h) => displaySymbol(h.symbol))
    .join(', ');

  const lines = [];
  if (counts.NO.length) {
    lines.push(el('div', { style: 'margin-bottom:6px' },
      el('strong', {}, 'Worth a second look: '),
      `${names('NO')}${counts.NO.length > 6 ? ' and others' : ''}. `,
      'These earn poorly on the capital they employ, or carry debt or cash-conversion problems. '
      + 'That is a reason to re-read the case for owning them, not a reason to sell today.'));
  }
  if (counts.COMPOUNDER.length) {
    lines.push(el('div', { style: 'margin-bottom:6px' },
      el('strong', {}, 'Your strongest businesses: '),
      `${names('COMPOUNDER')}${counts.COMPOUNDER.length > 6 ? ' and others' : ''}. `,
      'These are the ones where adding on weakness usually pays, and where selling on a bad '
      + 'quarter usually does not.'));
  }
  if (counts.NOT_MEASURED.length) {
    lines.push(el('div', {},
      el('strong', {}, `${counts.NOT_MEASURED.length} not measured: `),
      `${names('NOT_MEASURED')}${counts.NOT_MEASURED.length > 6 ? ' and others' : ''}. `,
      'The app has not screened these, so it has no view either way. Nothing here says they are '
      + 'weak.'));
  }

  return card(
    el('div.grid.kpis', {},
      tile('COMPOUNDER', 'Look like compounders', COMPOUNDING.COMPOUNDER.label, 'good'),
      tile('PARTIAL', 'Partly', 'pass some checks, not all', 'neutral'),
      tile('NO', 'Do not', 'fall short on most checks', counts.NO.length ? 'bad' : 'neutral'),
      tile('NOT_MEASURED', 'Not measured', 'never screened', 'neutral')),
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-top:12px' }, ...lines),
    el('div', {
      style: 'padding:11px 13px;background:var(--warn-bg);border-left:3px solid var(--warn);'
        + 'border-radius:var(--radius);font-size:13px;margin-top:10px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'Read this as quality, not as timing'),
    el('div', {},
      'Every check here is read from the ', el('strong', {}, 'latest year of accounts only'),
      ', so it says "this looks like a good business today" — not "this has compounded for '
      + 'years". One good year is what a commodity business shows at the top of its cycle. Open a '
      + 'stock’s own page for its multi-year record, and treat a weak verdict as a prompt to '
      + 're-read your reason for owning it rather than as an instruction to sell.')),
  );
}

/**
 * Sector mix on the RESOLVED sector (B-096). Unclassified holdings are named beside the chart,
 * never drawn as an "Other" slice: a slice that is 42% of the book and means "no data" tells the
 * reader the opposite of the truth.
 */
function sectorMix(holdings) {
  const byGroup = new Map();
  const unclassified = [];
  for (const h of holdings || []) {
    const s = sectorOf(h);
    if (!s) { unclassified.push(h); continue; }
    byGroup.set(s, (byGroup.get(s) || 0) + (h.currentValue || 0));
  }
  const slices = [...byGroup.entries()]
    .map(([label, value]) => ({ label, value }))
    .sort((a, b) => b.value - a.value);

  if (slices.length === 0) return empty('No sector data', 'No holding has a resolvable sector yet.');

  const classifiedTotal = slices.reduce((a, s) => a + s.value, 0);
  const all = totalValue(holdings);
  const chart = donut(slices);
  const legendRows = slices.slice(0, 10).map((s, i) => el('div.row.between', { style: 'font-size:12.5px' },
    el('span', {}, el('span.key', { style: `display:inline-block;width:11px;height:11px;border-radius:2px;margin-right:6px;background:${['#3949ab', '#2e7d32', '#f57c00', '#1976d2', '#c62828', '#6a1b9a', '#00838f', '#5d4037', '#9e9d24', '#546e7a'][i % 10]}` }), s.label),
    el('span.num', {}, pct((s.value / classifiedTotal) * 100, { signed: false }))));

  const coverage = unclassified.length
    ? el('div', { style: 'margin-top:10px;font-size:12.5px' },
      el('strong', {}, `${pct(all > 0 ? (unclassified.reduce((a, h) => a + (h.currentValue || 0), 0) / all) * 100 : 0, { signed: false })} of your money is not in this chart: `),
      `${unclassified.map((h) => displaySymbol(h.symbol)).join(', ')} ${unclassified.length === 1 ? 'has' : 'have'} no sector on record. `,
      'The percentages above are of the classified part only.')
    : null;

  return card(el('div.row.wrap', { style: 'gap:24px;align-items:flex-start' },
    chart, el('div', { style: 'flex:1;min-width:220px' }, legendRows)), coverage);
}

/**
 * Concentration, plus the thing HHI cannot see (SPEC 46.3): a low HHI on 29 positions with 14
 * of them under 2% is not "well spread", it is a book where half the positions cannot move the
 * outcome either way.
 */
function riskCard(risk) {
  if (!risk) return empty('Risk metrics unavailable', 'Could not compute concentration.');

  const hhi = risk.hhi ? risk.hhi.value : null;
  const g = gauge(hhi, {
    min: 0, max: 10000, label: humanLabel(risk.hhi && risk.hhi.classification),
    bands: [
      { upTo: 1500, color: '#2e7d32', label: 'Well spread' },
      { upTo: 2500, color: '#f57c00', label: 'Moderately concentrated' },
      { upTo: 10000, color: '#c62828', label: 'Concentrated' },
    ],
  });

  const alerts = (risk.alerts || []).map((a) => alert({ severity: a.severity, title: humanLabel(a.category), message: a.message }));

  const rows = data.holdings || [];
  const small = rows.filter((h) => (weightOf(h) || 0) < TOO_SMALL_PCT).sort((a, b) => (weightOf(a) || 0) - (weightOf(b) || 0));
  const smallShare = small.reduce((s, h) => s + (weightOf(h) || 0), 0);
  const top5 = [...rows].sort((a, b) => (b.currentValue || 0) - (a.currentValue || 0)).slice(0, 5);
  const top5Share = top5.reduce((s, h) => s + (weightOf(h) || 0), 0);

  const smallCard = small.length
    ? el('div', { style: 'margin-top:14px;padding:11px 13px;background:var(--warn-bg);border-left:3px solid var(--warn);border-radius:var(--radius);font-size:13px' },
      el('div', { style: 'font-weight:600;margin-bottom:4px' }, `${small.length} of ${rows.length} positions are too small to matter`),
      el('div', {}, `Together they are ${pct(smallShare, { signed: false })} of your money — each under ${TOO_SMALL_PCT}%. A 50% gain on one of them adds under 1% to the portfolio, and each still costs attention and a tax lot. `
        + 'The concentration gauge reads "well spread" partly because of them, which is the wrong reassurance. Either build them to a size that can matter, or let them go when a tax-neutral moment comes.'),
      el('div.muted', { style: 'margin-top:6px;font-size:12.5px' }, small.map((h) => `${displaySymbol(h.symbol)} ${pct(weightOf(h), { signed: false })}`).join(' · ')))
    : null;

  return card(
    el('div.row.wrap', { style: 'gap:20px;align-items:center' },
      g || el('div.muted', {}, NOT_MEASURED),
      el('div', { style: 'flex:1;min-width:240px' },
        el('div.row.between', { style: 'font-size:13px' },
          el('span.muted', {}, 'Biggest sector'),
          el('span', {}, risk.sectorConcentration ? `${humanLabel(risk.sectorConcentration.topBucket)} · ${pct(risk.sectorConcentration.topWeight, { signed: false })}` : NOT_MEASURED)),
        el('div.row.between', { style: 'font-size:13px' },
          el('span.muted', {}, 'Biggest single stock'),
          el('span', {}, risk.stockConcentration ? `${displaySymbol(risk.stockConcentration.topBucket)} · ${pct(risk.stockConcentration.topWeight, { signed: false })}` : NOT_MEASURED)),
        el('div.row.between', { style: 'font-size:13px' },
          el('span.muted', {}, 'Top 5 positions'),
          el('span', {}, rows.length ? pct(top5Share, { signed: false }) : NOT_MEASURED)),
        el('div.muted', { style: 'font-size:12px;margin-top:6px' }, 'HHI measures how packed your money is into few names. It says nothing about positions that are too small to count.'))),
    alerts.length ? el('div', { style: 'margin-top:14px' }, alerts) : null,
    smallCard);
}

/**
 * Drift by sector AND by market cap (B-096). The old panel filtered to sector rows only, which
 * hid the one bucket type that actually worked — the market-cap targets — behind a sector table
 * where every row was wrong.
 */
function driftPanel(drift) {
  if (!drift || !drift.buckets || drift.buckets.length === 0) {
    return empty('No allocation targets set',
      'Set target weights (via the API) and this shows how far each sector or stock has drifted from your plan.');
  }

  const group = (type) => {
    const targeted = drift.buckets.filter((b) => b.bucketType === type && b.alertLevel !== 'NO_TARGET');
    const untargeted = drift.buckets.filter((b) => b.bucketType === type && b.alertLevel === 'NO_TARGET' && b.actualWeight > 0.05);
    if (targeted.length === 0 && untargeted.length === 0) return null;
    const nodes = [el('div', { style: 'font-weight:600;margin:12px 0 6px' }, type === 'SECTOR' ? 'By sector' : 'By company size')];
    if (targeted.length) {
      nodes.push(table([
        { key: 'bucketKey', label: type === 'SECTOR' ? 'Sector' : 'Size', render: (b) => humanLabel(b.bucketKey) },
        { key: 'targetWeight', label: 'Target', align: 'r', render: (b) => pct(b.targetWeight, { signed: false }) },
        { key: 'actualWeight', label: 'Actual', align: 'r', render: (b) => pct(b.actualWeight, { signed: false }) },
        { key: 'driftPp', label: 'Drift', align: 'r', render: (b) => el('span', { class: sign(b.driftPp) }, pp(b.driftPp)) },
        { key: 'alertLevel', label: 'Status', render: (b) => badge(b.alertLevel) },
      ], targeted, { sortKey: 'driftPp' }));
    }
    if (untargeted.length) {
      nodes.push(el('div.muted', { style: 'font-size:12.5px;margin-top:6px' },
        el('strong', {}, 'Held with no target set: '),
        untargeted.sort((a, b) => b.actualWeight - a.actualWeight)
          .map((b) => `${humanLabel(b.bucketKey)} ${pct(b.actualWeight, { signed: false })}`).join(' · ')));
    }
    return el('div', {}, ...nodes);
  };

  const coverage = drift.unclassifiedWeightPercent > 0.05
    ? alert({
      severity: 'WARNING',
      title: `${pct(drift.unclassifiedWeightPercent, { signed: false })} of your money has no sector on record`,
      message: `${(drift.unclassifiedSymbols || []).map(displaySymbol).join(', ')}. These are left out of the sector rows rather than lumped into "Other", so the sector percentages are of the classified part only.`,
    })
    : null;

  return el('div', {}, coverage, group('SECTOR'), group('MARKET_CAP'));
}

function renderAnalysis() {
  const nodes = [];

  nodes.push(performanceSection());

  const wq = weightVsQualitySection();
  if (wq) nodes.push(wq);

  const compounding = compoundingSummary(data.holdings);
  if (compounding) {
    nodes.push(withCount(section('Can the businesses you own compound?',
      'Quality, not price. This asks whether each holding earns well on the money it employs, '
      + 'turns profit into cash, and stays out of trouble — the things that decide a '
      + 'ten-year return. It changes no score and is deliberately separate from the buy/sell '
      + 'signal.',
      compounding),
    (data.holdings || []).filter((h) => h.compounding === 'COMPOUNDER').length));
  }

  const pq = portfolioQualitySection();
  if (pq) nodes.push(pq);

  nodes.push(withCount(section('Everything you own',
    'Your full holdings, sortable by any column — click a heading to re-sort, or a stock to open its history. "Held" comes from your purchase lots and reads "not measured" when none is on file. The 90-day trend line is real recorded prices.',
    HOLDING_FILTERS.bar(data.holdings || [],
      HOLDING_FILTERS.apply(data.holdings || []).length, 'holdings'),
    holdingsTable(HOLDING_FILTERS.apply(data.holdings || []), data.matrix),
    // Mandatory beneath the Macro column (Gotcha 44): an empty-looking column must never be
    // read as "nothing is wrong" when it may mean "nothing was checked".
    macroCoverageLine(data.holdings || []),
    themeCoverageLine(data.holdings || [], 'holdings'),
    // Same rule for the Analysts column: a column full of "None on file" must not read as "the
    // market has no view on what I own" when it means this app's ledger is thin (SPEC 49.7).
    analystCoverageLine(data.holdings || []),
    // And for the Result column: an empty one reads as "none of my holdings reported anything
    // worrying", when it may mean no filed quarter has been captured at all (Gotcha 44).
    // The FILTERED count — this heading sits above the filtered table, and the chip bar
    // immediately under it reports the same narrowing in words.
    resultCoverageLine(data.holdings || [])), HOLDING_FILTERS.apply(data.holdings || []).length));

  const analyst = analystCoveragePanel(data.holdings || []);
  if (analyst) {
    nodes.push(withCount(section('Who else is covering what you own',
      'Which brokerages have a price target running on each of your holdings, and what they are '
      + 'quoting. This is other people’s opinion, recorded so it can be scored later — it is not '
      + 'this app’s view and it changes no score here. A stock with no target on file is usually '
      + 'one no published note reached, not one nobody follows.',
      analyst),
    (data.holdings || []).filter((h) => h.analystHouses).length));
  }

  nodes.push(withCount(section('How spread out is your money?',
    'Your holdings grouped by sector. A single slice dominating means your portfolio rises and falls with one part of the economy. Holdings with no sector on record are named, not hidden in a slice.',
    sectorMix(data.holdings)),
    new Set((data.holdings || []).map((h) => h.sector).filter(Boolean)).size));

  nodes.push(withSummary(section('Concentration risk',
    'HHI is a single concentration number: under 1,500 is well spread, above 2,500 means a few positions drive most of your outcome. Neither is right or wrong — it just needs to be deliberate. The opposite problem, positions too small to matter, is shown beside it.',
    riskCard(data.risk)), data.risk && data.risk.hhi && data.risk.hhi.classification));

  nodes.push(withSummary(section('Are you still following your plan?',
    'Drift compares what you actually hold against the target percentages you set, by sector and by company size. "Over Tolerance" means a bucket has moved further from target than the wiggle room you allowed.',
    driftPanel(data.drift)),
    data.drift && data.drift.buckets
      ? (data.drift.buckets.filter((b) => b.status === 'OVER_TOLERANCE').length
        ? `${data.drift.buckets.filter((b) => b.status === 'OVER_TOLERANCE').length} off target` : 'On target')
      : null,
    { type: data.drift && data.drift.buckets
      && data.drift.buckets.some((b) => b.status === 'OVER_TOLERANCE') ? 'warning' : 'success' }));

  return nodes;
}

// ----------------------------------------------------------------- TRENDS

function topMoversChart(holdings) {
  const live = (holdings || []).filter((h) => Number.isFinite(h.pnlPercent));
  if (live.length === 0) return empty('No returns to chart', 'No holdings with a recorded gain or loss.');

  const sorted = [...live].sort((a, b) => b.pnlPercent - a.pnlPercent);
  const picks = [...sorted.slice(0, 6), ...sorted.slice(-6)]
    .filter((h, i, arr) => arr.findIndex((x) => x.symbol === h.symbol) === i);

  const chart = barChart(picks.map((h) => ({
    label: displaySymbol(h.symbol),
    value: h.pnlPercent,
    note: inr(h.pnl),
  })), { width: 760, format: (v) => pct(v) });

  return card(chart || empty('Nothing to chart', ''));
}

/** Rupee contribution — which positions actually moved your wealth (SPEC 46.3). */
function contributionChart(holdings) {
  const live = (holdings || []).filter((h) => Number.isFinite(h.pnl) && h.pnl !== 0);
  if (live.length === 0) return empty('No contribution to chart', 'No holdings with a recorded gain or loss.');
  const sorted = [...live].sort((a, b) => b.pnl - a.pnl);
  const picks = [...sorted.slice(0, 6), ...sorted.slice(-5)]
    .filter((h, i, arr) => arr.findIndex((x) => x.symbol === h.symbol) === i);
  const chart = barChart(picks.map((h) => ({
    label: displaySymbol(h.symbol),
    value: h.pnl,
    note: `${pct(h.pnlPercent)} on ${pct(weightOf(h), { signed: false })} of your money`,
  })), { width: 760, format: (v) => inr(v) });
  return card(chart || empty('Nothing to chart', ''));
}

function scoreDistribution(holdings) {
  const buckets = [
    { label: '0-34 (Avoid)', lo: 0, hi: 34, color: '#c62828' },
    { label: '35-49 (Monitor)', lo: 35, hi: 49, color: '#f57c00' },
    { label: '50-64 (Watchlist)', lo: 50, hi: 64, color: '#1976d2' },
    { label: '65-79 (Good)', lo: 65, hi: 79, color: '#2e7d32' },
    { label: '80-100 (Strong)', lo: 80, hi: 100, color: '#1b5e20' },
  ];
  const scored = (holdings || []).filter((h) => Number.isFinite(h.overallScore));
  const unscored = (holdings || []).length - scored.length;

  if (scored.length === 0) {
    return empty('No scores recorded yet', 'Holdings have not been analysed yet, so there is nothing to distribute.');
  }

  const chart = barChart(buckets.map((b) => ({
    label: b.label,
    value: scored.filter((h) => h.overallScore >= b.lo && h.overallScore <= b.hi).length,
    color: b.color,
  })), { width: 620, format: (v) => String(Math.round(v)) });

  return card(chart,
    unscored > 0 ? el('div.muted', { style: 'font-size:12.5px;margin-top:8px' },
      `${unscored} holding${unscored === 1 ? '' : 's'} could not be scored and are excluded rather than counted as zero.`) : null);
}

function sectorPerformance(holdings) {
  const byGroup = new Map();
  let skipped = 0;
  for (const h of holdings || []) {
    if (!Number.isFinite(h.pnlPercent)) continue;
    const s = sectorOf(h);
    if (!s) { skipped++; continue; }
    if (!byGroup.has(s)) byGroup.set(s, []);
    byGroup.get(s).push(h);
  }
  if (byGroup.size === 0) return empty('No sector returns', 'Not enough data to group returns by sector.');

  const rows = [...byGroup.entries()].map(([sector, hs]) => ({
    label: sector,
    value: hs.reduce((a, h) => a + h.pnlPercent, 0) / hs.length,
    note: `${hs.length} stock${hs.length === 1 ? '' : 's'}`,
  })).sort((a, b) => b.value - a.value);

  return card(barChart(rows, { width: 760, format: (v) => pct(v) }),
    skipped ? el('div.muted', { style: 'font-size:12.5px;margin-top:8px' }, `${skipped} holding${skipped === 1 ? '' : 's'} with no sector on record left out.`) : null);
}

function renderTrends() {
  const nodes = [];

  nodes.push(withCount(section('Best and worst holdings',
    'Your six strongest and six weakest positions by percentage return since you bought. Bars to the right are gains, to the left are losses.',
    topMoversChart(data.holdings)), (data.holdings || []).length));

  nodes.push(withCount(section('Which positions actually moved your wealth?',
    'The same idea in rupees. A 60% gain on a small position and a 10% gain on a large one can be the same money — this is the chart that shows which of your decisions mattered.',
    contributionChart(data.holdings)), (data.holdings || []).length));

  nodes.push(withCount(section('Which sectors are working for you?',
    'Average return of your holdings grouped by sector. This shows where your gains are actually coming from, which is often not where you think.',
    sectorPerformance(data.holdings)),
    new Set((data.holdings || []).map((h) => h.sector).filter(Boolean)).size));

  nodes.push(withCount(section('Quality spread of what you own',
    'How your holdings’ scores are distributed. A cluster on the left means most of what you own currently rates poorly on the app’s own measures.',
    // Holdings the app has actually scored. One it has never screened has no place in a
    // distribution of scores, so it must not be in the count either (SPEC 21 rule 7).
    scoreDistribution(data.holdings)),
    (data.holdings || []).filter((h) => Number.isFinite(h.overallScore)).length));

  return nodes;
}

// ------------------------------------------------------------- PLAN & TAX

/**
 * The thesis panel (B-097). Counts are over theses the investor wrote; a generated one is
 * "not written yet", shown with its generated text as a starting point. The editor below is
 * the most valuable missing feature the review found: a text box.
 */
function convictionPanel(report) {
  if (!report) return empty('Conviction data unavailable', 'Could not read your thesis records.');

  const drifts = report.drifts || [];
  const stated = drifts.filter((d) => d.thesisStated);
  const seeded = drifts.filter((d) => !d.thesisStated);
  const missingThesis = report.holdingsMissingThesis || [];

  const cards = el('div.grid.kpis', {},
    kpi({ label: 'Thesis Intact', value: String(report.intactCount ?? 0), sub: 'written by you', tone: 'positive' }),
    kpi({ label: 'Under Review', value: String(report.underReviewCount ?? 0), sub: 'written by you', tone: 'warning' }),
    kpi({ label: 'Thesis Broken', value: String(report.brokenCount ?? 0), sub: 'written by you', tone: 'negative' }),
    kpi({ label: 'No Thesis Written', value: String(seeded.length + missingThesis.length), sub: `${seeded.length} app-generated · ${missingThesis.length} none at all`, tone: 'neutral' }));

  const body = stated.length
    ? table([
      { key: 'symbol', label: 'Stock', render: (d) => el('a', { href: stockHref(d.holdingSymbol || d.symbol) }, displaySymbol(d.symbol)) },
      { key: 'status', label: 'Status', render: (d) => badge(d.status) },
      { key: 'convictionScore', label: 'Conviction', align: 'r', render: (d) => (missing(d.convictionScore) ? unmeasured() : `${d.convictionScore}/10`) },
      { key: 'holdingHorizonMonths', label: 'Horizon', align: 'r', render: (d) => (missing(d.holdingHorizonMonths) ? unmeasured() : `${d.holdingHorizonMonths} mo`) },
      { key: 'purchaseMultibaggerScore', label: 'Score at Buy', align: 'r', render: (d) => (missing(d.purchaseMultibaggerScore) ? unmeasured('Not recorded at purchase') : num(d.purchaseMultibaggerScore)) },
      { key: 'currentMultibaggerScore', label: 'Score Now', align: 'r', render: (d) => (missing(d.currentMultibaggerScore) ? unmeasured('Not currently screened') : num(d.currentMultibaggerScore)) },
      { key: 'driftPoints', label: 'Change', align: 'r', render: (d) => (missing(d.driftPoints) ? unmeasured() : el('span', { class: sign(d.driftPoints) }, num(d.driftPoints))) },
      { key: 'thesis', label: 'Your Thesis', sortable: false, render: (d) => d.thesis || unmeasured('No thesis written') },
      { key: 'edit', label: '', sortable: false, render: (d) => el('button.action.secondary.compact', { onclick: () => openThesisEditor(d.holdingSymbol || d.symbol, d) }, 'Edit') },
    ], stated, { sortKey: 'driftPoints', sortDir: 'asc' })
    : empty('No thesis written by you yet',
      'A thesis is your written reason for owning a stock, in your words. The app generated placeholders for most holdings — they are counted as unwritten, because a reason the app made up is not a reason you can be held to.');

  const unwritten = [...seeded.map((d) => ({ symbol: d.holdingSymbol || d.symbol, generated: d.thesis, d })),
    ...missingThesis.map((s) => ({ symbol: s, generated: null, d: null }))]
    .sort((a, b) => a.symbol.localeCompare(b.symbol));

  const unwrittenList = unwritten.length
    ? el('div', { style: 'margin-top:14px' },
      el('div', { style: 'font-weight:600;margin-bottom:6px' }, `Waiting for your reason (${unwritten.length})`),
      el('div.row.wrap', { style: 'gap:6px' }, ...unwritten.map((u) => el('button.action.secondary.compact', {
        title: u.generated ? `App-generated placeholder: ${u.generated}` : 'No record at all',
        onclick: () => openThesisEditor(u.symbol, u.d),
      }, `${displaySymbol(u.symbol)} ›`))))
    : null;

  return el('div', {}, cards, el('div', { style: 'margin-top:14px' }, body), unwrittenList,
    el('div', { style: 'margin-top:16px' }, thesisEditor()));
}

let editorState = { symbol: '', thesis: '', conviction: 7, horizon: 36, triggers: '', busy: false, notice: null };

function openThesisEditor(symbol, d) {
  editorState = {
    symbol,
    thesis: d && d.thesisStated ? d.thesis : '',
    conviction: d && d.convictionScore ? d.convictionScore : 7,
    horizon: d && d.holdingHorizonMonths && d.thesisStated ? d.holdingHorizonMonths : 36,
    triggers: d && d.thesisStated ? (d.invalidationTriggers || '') : '',
    busy: false,
    notice: d && !d.thesisStated ? { severity: 'INFO', title: 'The app wrote this placeholder', message: d.thesis } : null,
  };
  paint('plan');
  const host = document.getElementById('thesis-editor');
  if (host) {
    // The editor lives inside a section that is folded by default, so it has to be revealed
    // before it can be scrolled to — otherwise clicking Edit appears to do nothing at all.
    revealSection(host);
    // Next frame: the section has just gone from hidden to laid out, and a smooth scroll
    // started in the same tick measures against the old layout and lands short.
    requestAnimationFrame(() => host.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  }
}

function thesisEditor() {
  const s = editorState;
  const held = (data.holdings || []).map((h) => h.symbol).sort();
  const select = el('select.field', { disabled: s.busy, onchange: (e) => { editorState.symbol = e.target.value; } },
    el('option', { value: '' }, 'Choose a holding…'),
    ...held.map((sym) => el('option', { value: sym, selected: sym === s.symbol ? 'selected' : null }, displaySymbol(sym))));
  const thesis = el('textarea.field.wide', {
    rows: 3, placeholder: 'Why do you own this? What has to be true for it to work? (your words, not the app’s)',
    disabled: s.busy, maxlength: 2000, oninput: (e) => { editorState.thesis = e.target.value; },
  });
  thesis.value = s.thesis;
  const triggers = el('textarea.field.wide', {
    rows: 2, placeholder: 'What would prove you wrong? e.g. "margins fall below 15% for two quarters"',
    disabled: s.busy, maxlength: 1000, oninput: (e) => { editorState.triggers = e.target.value; },
  });
  triggers.value = s.triggers;
  const conviction = el('input.field', { type: 'number', min: 1, max: 10, step: 1, title: 'Conviction 1-10', disabled: s.busy, oninput: (e) => { editorState.conviction = Number(e.target.value); } });
  conviction.value = String(s.conviction);
  const horizon = el('input.field', { type: 'number', min: 1, max: 240, step: 1, title: 'Holding horizon in months', disabled: s.busy, oninput: (e) => { editorState.horizon = Number(e.target.value); } });
  horizon.value = String(s.horizon);

  const button = el('button.action', { disabled: s.busy, onclick: submitThesis }, s.busy ? 'Saving…' : 'Save my thesis');

  return el('div#thesis-editor', {},
    el('div', { style: 'font-weight:600;margin-bottom:6px' }, 'Write or update a thesis'),
    s.notice ? alert(s.notice) : null,
    el('div.form-row', {}, select,
      el('label.muted', { style: 'font-size:12px' }, 'Conviction /10 ', conviction),
      el('label.muted', { style: 'font-size:12px' }, 'Horizon, months ', horizon)),
    el('div.form-row', {}, thesis),
    el('div.form-row', {}, triggers),
    el('div.form-row', {}, button,
      el('span.muted', { style: 'font-size:12px' }, 'Saved to your thesis record only. The horizon you enter is what the core-holding gate reads as your stated intent.')));
}

async function submitThesis() {
  const s = editorState;
  if (!s.symbol) { editorState.notice = { severity: 'WARNING', title: 'Choose a holding first', message: '' }; paint('plan'); return; }
  if (!s.thesis || s.thesis.trim().length < 10) { editorState.notice = { severity: 'WARNING', title: 'Write at least a sentence', message: 'A thesis you cannot be held to is not a thesis.' }; paint('plan'); return; }
  editorState.busy = true;
  paint('plan');
  try {
    await put('/api/portfolio/conviction', {
      symbol: s.symbol,
      thesis: s.thesis.trim(),
      convictionScore: Math.max(1, Math.min(10, s.conviction || 7)),
      holdingHorizonMonths: Math.max(1, s.horizon || 36),
      invalidationTriggers: (s.triggers || '').trim() || null,
      purchaseDate: new Date().toISOString().slice(0, 10),
    });
    const conviction = await get('/api/portfolio/conviction', { fallback: null, cache: false }).then((r) => r.data).catch(() => data.conviction);
    data = { ...data, conviction };
    editorState = { symbol: '', thesis: '', conviction: 7, horizon: 36, triggers: '', busy: false, notice: { severity: 'OPPORTUNITY', title: `Saved your thesis for ${displaySymbol(s.symbol)}`, message: '' } };
  } catch (err) {
    editorState.busy = false;
    editorState.notice = { severity: 'WARNING', title: 'Could not save', message: String(err && err.message ? err.message : err) };
  }
  paint('plan');
}

/**
 * Accumulation plans (SPEC 8), which the page never showed. A tranche whose date has passed
 * and is still pending is overdue and says so — the live INFY SIP had six of them.
 */
function accumulationPanel(plans) {
  const rows = (plans || []).filter((p) => p.status !== 'CANCELLED');
  if (rows.length === 0) {
    return empty('No accumulation plans', 'A plan splits a purchase into dated or price-laddered tranches so you buy on a schedule instead of on a mood. Create one via the API (SPEC 8).');
  }
  const today = new Date().toISOString().slice(0, 10);
  return el('div', {}, ...rows.map((p) => {
    const tranches = p.tranches || [];
    const overdue = tranches.filter((t) => t.status === 'PENDING' && t.triggerDate && t.triggerDate < today);
    const head = el('div.row.between.wrap', { style: 'align-items:center;margin-bottom:6px' },
      el('div', {},
        el('strong', {}, displaySymbol(p.symbol)), ' ',
        badge(p.mode, { type: 'info', label: humanLabel(p.mode) }), ' ',
        badge(p.status),
        el('span.muted', { style: 'font-size:12.5px' }, ` · ${inr(p.filledAmount)} of ${inr(p.targetAmount)} filled (${pct(p.progressPercent, { signed: false })})`)),
      el('div.row', { style: 'gap:6px' },
        p.status === 'ACTIVE' ? el('button.action.secondary.compact', { onclick: () => cancelPlan(p) }, 'Cancel plan') : null));
    const warn = overdue.length
      ? alert({ severity: 'WARNING', title: `${overdue.length} tranche${overdue.length === 1 ? '' : 's'} overdue`,
        message: `The earliest was due ${shortDate(overdue[0].triggerDate)}. Either the purchase was made and not recorded — mark it filled below — or the plan is stale and should be cancelled. An unfilled plan is not a plan.` })
      : null;
    const tbl = table([
      { key: 'trancheNumber', label: '#', align: 'r' },
      { key: 'amount', label: 'Amount', align: 'r', render: (t) => inr(t.amount) },
      { key: 'triggerDate', label: 'Due', render: (t) => (t.triggerDate ? shortDate(t.triggerDate) : unmeasured('Price-triggered')) },
      { key: 'triggerPrice', label: 'At price', align: 'r', render: (t) => (missing(t.triggerPrice) ? el('span.muted', {}, '—') : inrExact(t.triggerPrice, true)) },
      { key: 'status', label: 'Status', render: (t) => badge(t.status, t.status === 'PENDING' && t.triggerDate && t.triggerDate < today ? { type: 'warning', label: 'Overdue' } : {}) },
      { key: 'filled', label: 'Filled', sortable: false, render: (t) => (t.filledQuantity ? `${t.filledQuantity} @ ${inrExact(t.filledPrice, true)} on ${shortDate(t.filledDate)}` : el('span.muted', {}, '—')) },
      { key: 'act', label: '', sortable: false, render: (t) => (t.status === 'PENDING' ? fillControls(p, t) : null) },
    ], tranches, { sortKey: 'trancheNumber', sortDir: 'asc' });
    return card(head, warn, tbl);
  }));
}

function fillControls(plan, t) {
  const qty = el('input.field', { type: 'number', min: 1, step: 1, placeholder: 'qty', style: 'width:70px' });
  const price = el('input.field', { type: 'number', min: 0.05, step: 0.05, placeholder: 'price', style: 'width:90px' });
  const btn = el('button.action.secondary.compact', {
    onclick: async () => {
      const q = Number(qty.value);
      const pr = Number(price.value);
      if (!(q > 0) || !(pr > 0)) { planNotice = { severity: 'WARNING', title: 'Enter quantity and price', message: '' }; paint('plan'); return; }
      btn.disabled = true;
      try {
        await post(`/api/portfolio/accumulate/${plan.id}/tranche/${t.id}/fill`, { quantity: q, price: pr, fillDate: new Date().toISOString().slice(0, 10) });
        await reloadPlans(`Recorded tranche ${t.trancheNumber} of ${displaySymbol(plan.symbol)} as filled`);
      } catch (err) {
        planNotice = { severity: 'WARNING', title: 'Could not record the fill', message: String(err && err.message ? err.message : err) };
        paint('plan');
      }
    },
  }, 'Mark filled');
  return el('div.row', { style: 'gap:4px' }, qty, price, btn);
}

async function cancelPlan(p) {
  try {
    await del(`/api/portfolio/accumulate/${p.id}`);
    await reloadPlans(`Cancelled the ${displaySymbol(p.symbol)} plan`);
  } catch (err) {
    planNotice = { severity: 'WARNING', title: 'Could not cancel', message: String(err && err.message ? err.message : err) };
    paint('plan');
  }
}

let planNotice = null;

async function reloadPlans(msg) {
  const plans = await getList('/api/portfolio/accumulate').then((r) => r.data).catch(() => data.plans);
  data = { ...data, plans };
  planNotice = { severity: 'OPPORTUNITY', title: msg, message: '' };
  paint('plan');
}

/** Dividends (SPEC 11): logged income, what is announced but not yet received, and a log form. */
function dividendPanel(summary, events) {
  if (!summary) return empty('Dividend data unavailable', 'Could not read dividend records.');

  const cards = el('div.grid.kpis', {},
    kpi({ label: `Received (FY ${summary.fiscalYear ?? ''})`, value: inr(summary.totalReceived), sub: summary.eventCount === 0 ? 'nothing logged yet — this is unlogged, not unpaid' : `${summary.eventCount} events logged`, tone: summary.totalReceived > 0 ? 'positive' : 'neutral' }),
    kpi({ label: 'Announced, not yet received', value: inr(summary.totalProjected), tone: 'neutral', sub: 'at your current quantity' }),
    kpi({ label: 'Yield on cost', value: (() => {
      const invested = (data.holdings || []).reduce((s, h) => s + (h.investedValue || 0), 0);
      return invested > 0 && summary.totalReceived > 0 ? pct((summary.totalReceived / invested) * 100, { signed: false }) : NOT_MEASURED;
    })(), sub: 'dividends received this FY over what you paid', tone: 'neutral' }));

  const live = (events || []).filter((e) => e.status !== 'SKIPPED').sort((a, b) => String(b.exDate).localeCompare(String(a.exDate)));
  const tbl = live.length ? table([
    { key: 'symbol', label: 'Stock', render: (e) => displaySymbol(e.symbol) },
    { key: 'exDate', label: 'Ex-date', render: (e) => shortDate(e.exDate) },
    { key: 'amountPerShare', label: 'Per share', align: 'r', render: (e) => inrExact(e.amountPerShare, true) },
    { key: 'type', label: 'Type', render: (e) => humanLabel(e.type) },
    { key: 'status', label: 'Status', render: (e) => badge(e.status, { type: e.status === 'RECEIVED' ? 'success' : 'info' }) },
    { key: 'amount', label: 'Amount', align: 'r', value: (e) => e.totalReceived ?? e.projectedIncome, render: (e) => (e.status === 'RECEIVED' ? inr(e.totalReceived) : el('span.muted', {}, `${inr(e.projectedIncome)} expected`)) },
    {
      key: 'act', label: '', sortable: false,
      render: (e) => (e.status === 'ANNOUNCED' ? el('button.action.secondary.compact', {
        onclick: async () => {
          try {
            await post(`/api/portfolio/dividends/${e.id}/received`);
            await reloadDividends(`Marked ${displaySymbol(e.symbol)} dividend as received`);
          } catch (err) { planNotice = { severity: 'WARNING', title: 'Could not update', message: String(err && err.message ? err.message : err) }; paint('plan'); }
        },
      }, 'Received') : null),
    },
  ], live, { sortKey: 'exDate' }) : null;

  return el('div', {}, cards,
    tbl ? el('div', { style: 'margin-top:12px' }, tbl) : empty('No dividends logged yet',
      'The app has no dividend feed, so it will not invent income. Log each one below when the bank statement shows it, and "Total return" on the Analysis tab will include it.'),
    el('div', { style: 'margin-top:14px' }, dividendForm()));
}

function dividendForm() {
  const held = (data.holdings || []).map((h) => h.symbol).sort();
  const select = el('select.field', {}, el('option', { value: '' }, 'Stock…'), ...held.map((s) => el('option', { value: s }, displaySymbol(s))));
  const exDate = el('input.field', { type: 'date', title: 'Ex-dividend date' });
  const perShare = el('input.field', { type: 'number', min: 0.01, step: 0.01, placeholder: '₹ per share', style: 'width:120px' });
  const received = el('input.field', { type: 'number', min: 0, step: 1, placeholder: '₹ received (optional)', style: 'width:160px' });
  const btn = el('button.action', {
    onclick: async () => {
      if (!select.value || !exDate.value || !(Number(perShare.value) > 0)) { planNotice = { severity: 'WARNING', title: 'Stock, ex-date and per-share amount are needed', message: '' }; paint('plan'); return; }
      btn.disabled = true;
      const got = Number(received.value);
      try {
        await post('/api/portfolio/dividends', {
          symbol: select.value, exDate: exDate.value, amountPerShare: Number(perShare.value), type: 'FINAL',
          status: got > 0 ? 'RECEIVED' : 'ANNOUNCED', totalReceived: got > 0 ? got : null,
        });
        await reloadDividends(`Logged ${displaySymbol(select.value)} dividend`);
      } catch (err) { planNotice = { severity: 'WARNING', title: 'Could not log', message: String(err && err.message ? err.message : err) }; paint('plan'); }
    },
  }, 'Log a dividend');
  return el('div', {},
    el('div', { style: 'font-weight:600;margin-bottom:6px' }, 'Log a dividend'),
    el('div.form-row', {}, select, exDate, perShare, received, btn));
}

async function reloadDividends(msg) {
  const [summary, events] = await Promise.all([
    get('/api/portfolio/dividends/summary', { fallback: null, cache: false }).then((r) => r.data).catch(() => data.dividendSummary),
    getList('/api/portfolio/dividends').then((r) => r.data).catch(() => data.dividends),
  ]);
  data = { ...data, dividendSummary: summary, dividends: events };
  planNotice = { severity: 'OPPORTUNITY', title: msg, message: '' };
  paint('plan');
}

function taxPanel() {
  const host = el('div', {});
  const btn = el('button.action', {}, 'Show tax-harvest suggestions');

  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = 'Working…';
    try {
      const res = await postReadOnly('/api/portfolio/tax-lots/harvest');
      host.replaceChildren(harvestResult(res));
    } catch (e) {
      host.replaceChildren(alert({ severity: 'WARNING', title: 'Could not load suggestions', message: String(e.message || e) }));
    } finally {
      btn.disabled = false;
      btn.textContent = 'Refresh suggestions';
    }
  });

  const lots = data.performance && data.performance.lotCoverage;
  const coverage = lots
    ? (lots.holdingsWithLots < lots.holdings
      ? alert({ severity: 'WARNING', title: `Purchase lots on file for ${lots.holdingsWithLots} of ${lots.holdings} holdings`,
        message: `${lots.note} Missing: ${(lots.holdingsWithoutLots || []).map(displaySymbol).join(', ')}.` })
      : alert({ severity: 'OPPORTUNITY', title: 'Every holding has a purchase lot on file', message: '' }))
    : null;

  return el('div', {},
    coverage,
    costNote('Read-only. Nothing is bought or sold — this only shows which lots could reduce your tax bill if you chose to act.'),
    btn, el('div', { style: 'margin-top:14px' }, host));
}

function harvestResult(res) {
  if (!res) return empty('No response', '');

  const cards = el('div.grid.kpis', {},
    kpi({ label: 'Short-Term Gains Booked', value: inr(res.fiscalYearRealizedStcg), tone: 'neutral' }),
    kpi({ label: 'Long-Term Gains Booked', value: inr(res.fiscalYearRealizedLtcg), tone: 'neutral' }),
    kpi({ label: 'Exemption Left', value: inr(res.fiscalYearExemptionRemaining), tone: 'positive' }));

  const groups = [
    ['Losses you could book to offset gains', res.lossHarvestCandidates],
    ['Nearly long-term — waiting may cut your tax', res.approachingLtcgCutoff],
    ['Already long-term — taxed at the lower rate', res.ltcgEligible],
  ];

  const sections = groups.map(([title, rows]) => el('div', { style: 'margin-top:16px' },
    el('div', { style: 'font-weight:600;margin-bottom:7px' }, title),
    (rows && rows.length)
      ? table([
        { key: 'symbol', label: 'Stock', render: (l) => displaySymbol(l.symbol) },
        { key: 'remainingQuantity', label: 'Qty', align: 'r' },
        { key: 'buyPrice', label: 'Bought At', align: 'r', render: (l) => inrExact(l.buyPrice, true) },
        { key: 'buyDate', label: 'Bought On', render: (l) => shortDate(l.buyDate) },
        { key: 'daysHeld', label: 'Days Held', align: 'r' },
        { key: 'daysToLtcgCutoff', label: 'Days to Long-Term', align: 'r', render: (l) => (missing(l.daysToLtcgCutoff) || l.daysToLtcgCutoff <= 0 ? badge('LONG_TERM', { label: 'Already there' }) : num(l.daysToLtcgCutoff)) },
        { key: 'unrealizedGain', label: 'Unbooked Gain', align: 'r', render: (l) => el('span', { class: sign(l.unrealizedGain) }, inr(l.unrealizedGain)) },
      ], rows, { sortKey: 'unrealizedGain', sortDir: 'asc' })
      : el('div.muted', { style: 'font-size:13px' }, 'Nothing in this group.')));

  return el('div', {}, cards, ...sections);
}

function rebalancePanel() {
  const host = el('div', {});
  const btn = el('button.action', {}, 'Show a rebalance plan');

  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = 'Working…';
    try {
      const res = await postReadOnly('/api/portfolio/rebalance');
      const trades = (res && res.trades) || [];
      host.replaceChildren(trades.length
        ? el('div', {},
          el('div.row.wrap', { style: 'gap:14px;margin-bottom:12px' },
            kpi({ label: 'Total to Buy', value: inr(res.totalBuyValue), tone: 'positive' }),
            kpi({ label: 'Total to Sell', value: inr(res.totalSellValue), tone: 'negative' }),
            kpi({ label: 'Estimated Cost', value: inr(res.estimatedCost), tone: 'warning' })),
          table([
            { key: 'symbol', label: 'Stock', render: (t) => displaySymbol(t.symbol) },
            { key: 'action', label: 'Action', render: (t) => badge(t.action === 'BUY' ? 'BUY' : 'SELL') },
            { key: 'quantity', label: 'Qty', align: 'r' },
            { key: 'amount', label: 'Amount', align: 'r', render: (t) => inr(t.amount) },
            { key: 'currentWeight', label: 'Now', align: 'r', render: (t) => pct(t.currentWeight, { signed: false }) },
            { key: 'targetWeight', label: 'Target', align: 'r', render: (t) => pct(t.targetWeight, { signed: false }) },
            { key: 'driftPp', label: 'Drift', align: 'r', render: (t) => el('span', { class: sign(t.driftPp) }, pp(t.driftPp)) },
            { key: 'reason', label: 'Why', sortable: false },
          ], trades, { sortKey: 'amount' }))
        : empty('No rebalancing needed', 'Your holdings are already within tolerance of your targets.'));
    } catch (e) {
      host.replaceChildren(alert({ severity: 'WARNING', title: 'Could not build a plan', message: String(e.message || e) }));
    } finally {
      btn.disabled = false;
      btn.textContent = 'Refresh plan';
    }
  });

  const unclassified = data.drift && data.drift.unclassifiedWeightPercent > 5
    ? alert({ severity: 'WARNING', title: `${pct(data.drift.unclassifiedWeightPercent, { signed: false })} of your money has no sector on record`,
      message: 'A rebalance plan built on sector targets will ignore those holdings. Read its sector rows with that in mind; the company-size rows are unaffected.' })
    : null;

  return el('div', {},
    unclassified,
    costNote('Read-only. This proposes trades for you to consider — the app never places an order.'),
    btn, el('div', { style: 'margin-top:14px' }, host));
}

function renderPlan() {
  const nodes = [];
  if (planNotice) { nodes.push(alert(planNotice)); planNotice = null; }

  nodes.push(withCount(section('Why you own what you own',
    'Your written reason (thesis) for each holding, and whether the numbers still back it up. "Score at buy" versus "score now" is the honest test of whether your original case is holding together. Only theses you wrote count — the app’s generated placeholders are listed as waiting for yours.',
    // Theses the INVESTOR wrote. The app seeds a placeholder for every holding, and counting
    // those would report the app agreeing with itself as a record of your own reasoning (B-097).
    convictionPanel(data.conviction)),
    (data.conviction && data.conviction.statedCount) || 0));

  nodes.push(withCount(section('Staged purchases you have planned',
    'An accumulation plan splits a purchase into dated instalments or price rungs, so the buying happens on a schedule you set in a calm moment. A tranche past its date and still pending is overdue: record it or cancel the plan.',
    accumulationPanel(data.plans)), (data.plans || []).length));

  nodes.push(section('Tax-aware selling',
    'Selling shares held under 365 days triggers STCG at 20%; held longer, LTCG applies at a lower rate with an annual exemption. This shows which lots are worth waiting on and which losses could offset gains you have already booked.',
    taxPanel()));

  nodes.push(section('Getting back to your target mix',
    'A proposed set of trades to bring your holdings back to the target weights you set. Nothing is executed — treat it as a shopping list to review.',
    rebalancePanel()));

  nodes.push(withSummary(section('Dividend income',
    'Cash your holdings have paid you this financial year (April to March), and what is announced but not yet received. Dividends are real return that price charts do not show — and the app cannot see them, so what you log here is what counts.',
    dividendPanel(data.dividendSummary, data.dividends)),
    // Received this financial year. Projected income is a forecast and does not belong in the
    // one line the reader uses to decide whether to open the section.
    data.dividendSummary ? inr(data.dividendSummary.totalReceived) : null,
    { type: 'info' }));

  return nodes;
}

// -------------------------------------------------------------------- boot

const RENDERERS = { actions: renderActions, analysis: renderAnalysis, trends: renderTrends, plan: renderPlan };

function renderTabs(active) {
  const bar = el('div.filters', { style: 'margin-bottom:20px' });
  for (const t of TABS) {
    const chip = el('button.chip', { 'aria-pressed': String(t.id === active) }, t.label);
    chip.addEventListener('click', () => {
      window.location.hash = t.id;
      paint(t.id);
    });
    bar.append(chip);
  }
  return bar;
}

function paint(tab, opts = {}) {
  const nodes = [renderTabs(tab)];
  if (refreshNotice) {
    nodes.push(alert({ severity: 'WARNING', title: refreshNotice.title, message: refreshNotice.message }));
  }
  nodes.push(...(RENDERERS[tab] || renderActions)());
  mount(view, nodes);

  // Typing in the portfolio filter re-paints the tab, so the box must not lose the cursor on
  // every keystroke (same as the screener and the watchlist).
  if (opts.keepFocus) {
    const box = view.querySelector('input[type="search"]');
    if (box) {
      box.focus();
      box.setSelectionRange(box.value.length, box.value.length);
    }
  }
}

let busy = new Set();
let refreshNotice = null;

function currentTab() {
  const t = (window.location.hash || '#actions').slice(1);
  return TABS.some((x) => x.id === t) ? t : 'actions';
}

async function boot() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading your portfolio…'), skeleton(4)));
  // initChrome already fetches health for the freshness strip; reuse it rather than asking twice.
  const health = await initChrome();

  const [holdings, exitC, accC, decay, risk, drift, conviction, divSummary, dividends, matrix, core, buyTiming, performance, quality, plans] = await Promise.all([
    getList('/api/trading/holdings').then((r) => r.data).catch(() => []),
    getList('/api/trading/holdings/exit-candidates').then((r) => r.data).catch(() => []),
    getList('/api/trading/holdings/accumulate-candidates').then((r) => r.data).catch(() => []),
    getList('/api/trading/holdings/decay').then((r) => r.data).catch(() => []),
    get('/api/portfolio/risk', { fallback: null }).then((r) => r.data).catch(() => null),
    get('/api/portfolio/drift', { fallback: null }).then((r) => r.data).catch(() => null),
    get('/api/portfolio/conviction', { fallback: null }).then((r) => r.data).catch(() => null),
    get('/api/portfolio/dividends/summary', { fallback: null }).then((r) => r.data).catch(() => null),
    getList('/api/portfolio/dividends').then((r) => r.data).catch(() => []),
    get('/api/dashboard/series/matrix?days=90', { fallback: {} }).then((r) => r.data).catch(() => ({})),
    // DB-only: reads the latest holding_classification rows. Verified by hand, per the rule
    // that a page-load get() is ungated — the on-demand allowlist does not protect this path.
    get('/api/portfolio/core-holdings', { fallback: null }).then((r) => r.data).catch(() => null),
    // DB-only: repository reads plus the pure verdict rule table, no broker or NSE call.
    get('/api/trading/holdings/buy-timing', { fallback: {} }).then((r) => r.data).catch(() => ({})),
    // SPEC 46: DB-only - daily snapshots, stored benchmark closes, the cash snapshot, tax lots.
    get('/api/portfolio/performance?days=365', { fallback: null }).then((r) => r.data).catch(() => null),
    get('/api/portfolio/quality', { fallback: null }).then((r) => r.data).catch(() => null),
    getList('/api/portfolio/accumulate').then((r) => r.data).catch(() => []),
  ]);

  data = {
    holdings, exitCandidates: exitC, accumulateCandidates: accC, decay, risk, drift,
    conviction, dividendSummary: divSummary, dividends, matrix, core, buyTiming, health,
    performance, quality, plans,
  };

  const initial = (window.location.hash || '#actions').slice(1);
  paint(TABS.some((t) => t.id === initial) ? initial : 'actions');
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load your portfolio', String(err && err.message ? err.message : err))));
});
