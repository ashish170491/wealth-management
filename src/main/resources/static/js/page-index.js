/**
 * Overview — a triage screen, not a data dump.
 *
 * It answers two questions and then gets out of the way: "is my money growing?" and "is
 * anything wrong today?". Everything else is one click away on another page.
 *
 * Every request here is DB-only and under the 2-second page-load budget (SPEC section 18).
 */

import { get, getList } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import {
  inr,
  pct,
  num,
  corr,
  humanLabel,
  displaySymbol,
  missing,
  sign,
  NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, card, alert, empty, skeleton, mount, badge,
} from './ui.js';
import { lineChart, legend, barChart, COLORS } from './charts.js';
import { pp } from './format.js';

const view = document.getElementById('view');

function loading() {
  view.replaceChildren(
    el('section.section', {}, el('h2.section-title', {}, 'Loading your portfolio…'), skeleton(5)));
}

// --------------------------------------------------------------------- KPIs

function kpiRow(p) {
  if (!p) return empty('Portfolio summary unavailable', 'Could not read your holdings.');

  const grid = el('div.grid.kpis');
  grid.append(
    kpi({ label: 'Current Value', value: inr(p.currentValue), sub: `${inr(p.investedValue)} invested`, tone: 'neutral' }),
    kpi({ label: 'Total Gain / Loss', value: inr(p.pnl), raw: p.pnl, tone: 'auto', sub: pct(p.pnlPercent) }),
    kpi({ label: "Today's Change", value: inr(p.dayChangeValue), raw: p.dayChangeValue, tone: 'auto', sub: pct(p.dayChangePercent) }),
    kpi({ label: 'Winners vs Losers', value: `${p.profitableCount} / ${p.losingCount}`, sub: `${p.holdingsCount} stocks held`, tone: 'neutral' }));
  return grid;
}

// ------------------------------------------------------------- equity curve

function equityCurve(points) {
  if (!points || points.length < 2) {
    return empty('Not enough history yet',
      'This chart needs at least two daily snapshots. The app records one per weekday it runs, so it fills in over time.');
  }

  const series = [
    { name: 'Market value', color: COLORS.navyLight, fill: true, points: points.map((p) => ({ x: p.d, y: p.value })), format: (v) => inr(v) },
    { name: 'What you paid', color: COLORS.faint, points: points.map((p) => ({ x: p.d, y: p.invested })), format: (v) => inr(v) },
  ];

  const chart = lineChart(series, { height: 270, formatLeft: (v) => inr(v) });
  if (!chart) return empty('Not enough history yet', 'No usable data points in this window.');

  const first = points[0];
  const last = points[points.length - 1];
  const change = last.value - first.value;

  return card(
    el('div.row.between.wrap', {},
      el('div', {},
        el('div.muted', { style: 'font-size:12.5px' }, `${points.length} daily snapshots`),
        el('div', { style: 'font-size:15px;font-weight:600' },
          el('span', {}, 'Value moved '),
          el('span', { class: sign(change) }, inr(change)),
          el('span', {}, ' over this period'))),
      legend(series)),
    chart);
}

/**
 * The number the equity curve cannot show (SPEC 46.1): the return with deposits and withdrawals
 * removed, beside the index. The curve read "+17%" in February and "+17%" in September while
 * seven flat months went by; this line is what would have said so.
 */
function performanceStrip(p) {
  if (!p) return null;
  const n50 = (p.benchmarks || []).find((b) => b.symbol === 'NSE:NIFTY 50') || {};
  const dd = p.drawdown || {};
  const tone = (v) => (missing(v) ? 'neutral' : v > 0 ? 'positive' : v < 0 ? 'negative' : 'neutral');
  return el('div.grid.kpis', { style: 'margin-top:12px' },
    kpi({
      label: `Time-weighted return, ${p.daysSpanned || 0} days`,
      value: missing(p.twrPercent) ? NOT_MEASURED : pct(p.twrPercent),
      sub: missing(p.twrAnnualisedPercent) ? 'deposits and withdrawals removed' : `${pct(p.twrAnnualisedPercent)} a year`,
      tone: tone(p.twrPercent),
    }),
    kpi({
      label: 'Against the Nifty 50',
      value: missing(p.excessVsNifty50Pp) ? NOT_MEASURED : pp(p.excessVsNifty50Pp),
      sub: missing(n50.returnPercent) ? (n50.coverage || 'no index data stored yet') : `index moved ${pct(n50.returnPercent)}`,
      tone: tone(p.excessVsNifty50Pp),
    }),
    kpi({
      label: 'Below your peak',
      value: missing(dd.currentDrawdownPercent) ? NOT_MEASURED : (dd.currentDrawdownPercent === 0 ? 'At a high' : pct(dd.currentDrawdownPercent)),
      sub: missing(dd.maxDrawdownPercent) ? '' : `deepest fall so far ${pct(dd.maxDrawdownPercent)}`,
      tone: dd.currentDrawdownPercent < -10 ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Cash at the broker',
      value: p.cash && !missing(p.cash.availableCash) ? inr(p.cash.availableCash) : NOT_MEASURED,
      sub: p.cash && p.cash.asOf ? `as of ${p.cash.asOf}` : 'captured at the 15:00 snapshot',
      tone: 'neutral',
    }));
}

// ------------------------------------------------------------ attention list

function attentionList(items) {
  if (!items || items.length === 0) {
    return el('div.alert.success', {},
      el('div.alert-title', {}, 'Nothing needs your attention today'),
      el('div', {}, 'No exit signals, no thesis drift, and your allocation is within the tolerance you set.'));
  }

  const shown = items.slice(0, 12);
  const nodes = shown.map((i) => alert({
    severity: i.severity,
    title: `${i.symbol ? displaySymbol(i.symbol) + ' — ' : ''}${i.headline}`,
    message: i.detail,
    meta: missing(i.pnlPercent) ? humanLabel(i.kind) : `${humanLabel(i.kind)} · you are ${pct(i.pnlPercent)} on this holding`,
  }));

  if (items.length > shown.length) {
    nodes.push(el('div.muted', { style: 'font-size:13px' },
      `and ${items.length - shown.length} more — see My Portfolio for the full list.`));
  }
  return nodes;
}

// ------------------------------------------------------------------- risk

function riskPanel(r) {
  if (!r) return empty('Risk metrics unavailable', 'Could not compute concentration for your portfolio.');

  const rows = [];
  if (!missing(r.hhi)) {
    rows.push({ label: 'HHI', value: r.hhi, note: humanLabel(r.hhiClassification) });
  }

  return card(
    el('div.row.between.wrap', { style: 'margin-bottom:10px' },
      el('div', {},
        el('div.label.muted', { style: 'font-size:11.5px;text-transform:uppercase;letter-spacing:.5px;font-weight:600' }, 'Concentration'),
        el('div', { style: 'font-size:23px;font-weight:600;color:var(--navy)' },
          missing(r.hhi) ? NOT_MEASURED : num(r.hhi)),
        el('div.muted', { style: 'font-size:12.5px' },
          missing(r.hhiClassification) ? '' : humanLabel(r.hhiClassification))),
      r.alertCount > 0
        ? badge('WARNING', { label: `${r.alertCount} risk alert${r.alertCount === 1 ? '' : 's'}` })
        : badge('LOW', { label: 'No risk alerts' })),
    el('div', { style: 'font-size:13px' },
      el('div.row.between', {},
        el('span.muted', {}, 'Biggest sector'),
        el('span', {}, missing(r.topSectorWeight) ? NOT_MEASURED : `${humanLabel(r.topSector)} · ${pct(r.topSectorWeight, { signed: false })}`)),
      el('div.row.between', {},
        el('span.muted', {}, 'Biggest single stock'),
        el('span', {}, missing(r.topStockWeight) ? NOT_MEASURED : `${displaySymbol(r.topStock)} · ${pct(r.topStockWeight, { signed: false })}`))));
}

// --------------------------------------------------------------- accuracy

function accuracyPanel(a) {
  if (!a) {
    return empty('No track record yet',
      'Once the app has issued picks and they reach their 90-day mark, this is where you will see whether they actually worked.');
  }

  // Below the display threshold we show the sample size and nothing else. A hit rate from
  // 3 picks looks exactly as authoritative as one from 300 (SPEC section 21 rule 7).
  if (!a.enoughData) {
    return card(
      el('div', { style: 'font-weight:600;margin-bottom:4px' }, `${humanLabel(a.source)} · ${a.horizonDays}-day results`),
      el('div.muted', { style: 'font-size:13.5px' },
        `Only ${a.sampleSize} pick${a.sampleSize === 1 ? '' : 's'} have reached this horizon so far — too few to draw any conclusion from, so the numbers are withheld rather than shown misleadingly.`));
  }

  return card(
    el('div', { style: 'font-weight:600;margin-bottom:8px' },
      `${humanLabel(a.source)} · ${a.horizonDays}-day results`,
      el('span.muted', { style: 'font-weight:400;font-size:12.5px' }, ` — from ${a.sampleSize} picks`)),
    el('div.grid.three', {},
      el('div', {},
        el('div.muted', { style: 'font-size:12px' }, 'Hit rate'),
        el('div', { style: 'font-size:19px;font-weight:600' }, pct(a.hitRatePercent, { signed: false }))),
      el('div', {},
        el('div.muted', { style: 'font-size:12px' }, 'Excess return vs Nifty'),
        el('div', { class: sign(a.meanExcessReturnPercent), style: 'font-size:19px;font-weight:600' }, pct(a.meanExcessReturnPercent))),
      el('div', {},
        el('div.muted', { style: 'font-size:12px' }, 'IC'),
        el('div', { style: 'font-size:19px;font-weight:600' }, corr(a.informationCoefficient)))));
}

// ------------------------------------------------------------- top movers

function topMovers(holdings) {
  const live = (holdings || []).filter((h) => Number.isFinite(h.dayChangePercent) && h.dayChangePercent !== 0);
  if (live.length === 0) {
    return empty('No movement recorded today', 'Either the market has not moved your stocks, or prices have not synced yet today.');
  }

  const sorted = [...live].sort((a, b) => b.dayChangePercent - a.dayChangePercent);
  const picks = [...sorted.slice(0, 3), ...sorted.slice(-3).reverse()]
    .filter((h, i, arr) => arr.findIndex((x) => x.symbol === h.symbol) === i);

  const chart = barChart(picks.map((h) => ({
    label: displaySymbol(h.symbol),
    value: h.dayChangePercent,
    note: inr(h.dayChange),
  })), { width: 620, format: (v) => pct(v) });

  return card(chart || empty('No movement to chart', ''));
}

// ------------------------------------------------------------------- boot

async function render() {
  loading();
  await initChrome();

  const [summaryRes, seriesRes, holdingsRes, perfRes] = await Promise.all([
    get('/api/dashboard/summary', { fallback: null }).catch(() => ({ data: null })),
    get('/api/dashboard/series/portfolio?days=365', { fallback: [] }).catch(() => ({ data: [] })),
    getList('/api/trading/holdings').catch(() => ({ data: [] })),
    // DB-only (SPEC 46): snapshots, stored benchmark closes, the cash snapshot.
    get('/api/portfolio/performance?days=365', { fallback: null }).catch(() => ({ data: null })),
  ]);

  const s = summaryRes.data;
  const nodes = [];

  nodes.push(section('Where you stand',
    'A snapshot of everything you own right now. "Current value" is what your holdings are worth today; "total gain or loss" compares that against what you originally paid.',
    kpiRow(s && s.portfolio)));

  nodes.push(section('Is your money growing?',
    'Your portfolio value day by day, against what you paid for it. The gap between the two lines is your profit — but that gap cannot move when you add or withdraw money, so the tiles below remove your deposits and withdrawals and set the result against the index. Missing days are days the app was not running; the line breaks rather than guessing.',
    equityCurve(seriesRes.data), performanceStrip(perfRes.data)));

  nodes.push(section('What needs your attention today',
    'Everything the app thinks is worth a look, most urgent first: stocks flagged to exit, holdings whose original reason for buying is weakening, and drift from the allocation you set. These are prompts to review, never instructions to trade.',
    attentionList(s && s.attention)));

  nodes.push(section('Risk and track record',
    'On the left, how concentrated your portfolio is — HHI rises when your money is packed into fewer stocks or sectors. On the right, whether the app’s own past picks actually beat the index.',
    el('div.grid.two', {}, riskPanel(s && s.risk), accuracyPanel(s && s.accuracy))));

  nodes.push(section("Today's biggest moves",
    'Your three best and three worst performers today, by percentage. Useful for spotting a surprise, not for making decisions.',
    topMovers(holdingsRes.data)));

  mount(view, nodes);
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
render().then(() => registerRefresh(render)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '',
    empty('Could not load the dashboard', String(err && err.message ? err.message : err))));
});
