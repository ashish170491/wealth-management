/**
 * Overview — a triage screen, not a data dump.
 *
 * It answers two questions and then gets out of the way: "is my money growing?" and "is
 * anything wrong today?". Everything else is one click away on another page.
 *
 * Every request here is DB-only and under the 2-second page-load budget (SPEC section 18).
 */

import { get, getList } from './api.js';
import { initChrome, registerRefresh, currentFreshness } from './nav.js';
import {
  inr,
  pct,
  num,
  corr,
  humanLabel,
  displaySymbol,
  missing,
  sign,
  shortDate,
  daysAgo,
  NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, card, alert, empty, skeleton, mount, badge, withCount, withSummary,
} from './ui.js';
import { lineChart, legend, barChart, COLORS } from './charts.js';
import { pp } from './format.js';
import { analystCoverageStrip } from './analyst-cells.js';

const view = document.getElementById('view');

/**
 * Both benchmarks, always, and in this order.
 *
 * Showing one is a framing choice the reader cannot see. Measured on the live book over the
 * same 210 days: the Nifty 50 fell 8.52% (so the portfolio is 3.81 pp AHEAD of it) while the
 * Midcap 150 rose 3.47% (so it is 8.18 pp BEHIND). The "am I beating the market?" answer flips
 * sign depending on which one is drawn - and the book is mid/small-tilted, so the omitted one
 * was the more appropriate yardstick. Both figures were already computed and one was dropped.
 */
const BENCHMARKS = [
  { symbol: 'NSE:NIFTY 50', label: 'Nifty 50', excessField: 'excessVsNifty50Pp' },
  { symbol: 'NSE:NIFTY MIDCAP 150', label: 'Nifty Midcap 150', excessField: 'excessVsMidcap150Pp' },
];

/**
 * How far apart gain-on-cost and the time-weighted return must be before the page explains it.
 *
 * Not zero: these two answer different questions and will always differ a little, and a callout
 * that appears every day explaining nothing is the noise that trains a reader to skip the box on
 * the day it matters (SPEC 27.13's rule about a narrowed list, applied to an explanation).
 */
const DIVERGENCE_PP = 5;

function loading() {
  view.replaceChildren(
    el('section.section', {}, el('h2.section-title', {}, 'Loading your portfolio…'), skeleton(5)));
}

// ------------------------------------------------------------ dating prices

/**
 * When the prices behind this page were actually pulled.
 *
 * The app runs 09:15-15:30, so at 09:13 on a weekday - or at any hour of a weekend - the newest
 * prices are the PREVIOUS session's. Calling that "today's change" is a claim about a day the
 * market has not traded yet: read at 09:13 on 18 Sep the page reported a move of -52.23 as
 * today's, from a sync stamped 17 Sep 15:18. Same family as B-119 - a figure quoted on a scale
 * it was not measured on - and the freshness strip tinting amber does not undo the word "today"
 * printed three times in the body.
 *
 * Reads the stamp the strip already fetched rather than making a fifth request, so the two can
 * never disagree about what the app last did. Three distinct answers, because "we do not know"
 * must not collapse into "today" (SPEC 21 rule 7).
 */
function priceAsOf() {
  const freshness = currentFreshness();
  const stamp = freshness && freshness.holdingsSynced;
  if (!stamp) return { known: false, today: false, label: null };
  const days = daysAgo(stamp);
  if (days === null) return { known: false, today: false, label: null };
  return { known: true, today: days <= 0, label: shortDate(stamp) };
}

/** "Today's Change" only when the prices really are today's. */
function dayChangeLabel(asOf) {
  if (!asOf.known) return "Latest session's change";
  return asOf.today ? "Today's Change" : `Change on ${asOf.label}`;
}

/** The same answer as a phrase, for prose. */
function sessionPhrase(asOf) {
  if (!asOf.known) return 'the most recent session the app has prices for';
  return asOf.today ? 'today' : asOf.label;
}

// --------------------------------------------------------------------- KPIs

function kpiRow(p, asOf) {
  if (!p) return empty('Portfolio summary unavailable', 'Could not read your holdings.');

  const grid = el('div.grid.kpis');
  grid.append(
    kpi({ label: 'Current Value', value: inr(p.currentValue), sub: `${inr(p.investedValue)} invested`, tone: 'neutral' }),
    kpi({ label: 'Total Gain / Loss', value: inr(p.pnl), raw: p.pnl, tone: 'auto', sub: pct(p.pnlPercent) }),
    kpi({ label: dayChangeLabel(asOf), value: inr(p.dayChangeValue), raw: p.dayChangeValue, tone: 'auto', sub: pct(p.dayChangePercent) }),
    kpi({ label: 'Winners vs Losers', value: `${p.profitableCount} / ${p.losingCount}`, sub: `${p.holdingsCount} stocks held`, tone: 'neutral' }));
  return grid;
}

/**
 * Names the gap between gain-on-cost and the time-weighted return (SPEC 46.1, Gotcha 105).
 *
 * On the live book these read +14.39% and -4.71%: the headline says the portfolio is up a
 * seventh while the money is actually down. Both are correct - gain-on-cost cannot move when
 * cash comes in or goes out, which is the whole reason SPEC 46 exists - so the GAP is the
 * finding and it is explained rather than reconciled. Merging them would destroy the
 * distinction the second figure exists to draw; leaving them silent reads as the app arguing
 * with itself, and SPEC 21's reader is not an expert.
 *
 * Built with `.info-box`, which is the "what this means" box defined in app.css. Not `.callout`
 * and not `var(--accent)` - neither exists, and a missing class or variable fails silently with
 * the attribute still present in the DOM (Gotcha 104, 126n).
 */
function gainVsGrowthNote(p, perf) {
  if (!p || !perf || missing(p.pnlPercent) || missing(perf.twrPercent)) return null;

  const onCost = p.pnlPercent;
  const timeWeighted = perf.twrPercent;
  const gapPp = onCost - timeWeighted;
  if (Math.abs(gapPp) < DIVERGENCE_PP) return null;

  const oppositeWays = (onCost >= 0) !== (timeWeighted >= 0);
  return el('div.info-box', {},
    el('b', {}, oppositeWays
      ? 'These two numbers point opposite ways, and both are right.'
      : 'These two numbers disagree, and both are right.'),
    el('div', { style: 'margin-top:6px' },
      `Your stocks are worth ${pct(onCost)} more than you paid for them — that is the `
      + `${inr(p.pnl)} above. But the money itself grew ${pct(timeWeighted)} over the `
      + `${perf.daysSpanned || 0} days measured, once the cash you paid in and took out is removed.`),
    el('div', { style: 'margin-top:6px' },
      `They are ${num(Math.abs(gapPp), 1)} percentage points apart. A gap that size means money `
      + `moved in or out of the account — not that either figure is wrong. The first answers `
      + `"what are my stocks worth against what I paid". The second answers "how well did my `
      + `money actually do", and it is the one to set against an index.`));
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
  const investedChange = last.invested - first.invested;

  // "Value moved -Rs 11,730" is the gain-on-cost failure mode in rupees, printed in semibold
  // directly above the four tiles that exist to replace it: over this window the cost basis moved
  // by Rs 53,494, about a fifth of the book, so the figure is mostly deposits and withdrawals. It
  // is still worth showing - it is what the chart draws - but it is labelled as what it is, and
  // the money paid in is named beside it so the two cannot be read as one.
  return card(
    el('div.row.between.wrap', {},
      el('div', {},
        el('div.muted', { style: 'font-size:12.5px' }, `${points.length} daily snapshots`),
        el('div', { style: 'font-size:15px;font-weight:600' },
          el('span', {}, 'Market value moved '),
          el('span', { class: sign(change) }, inr(change)),
          el('span', {}, ' over this period')),
        el('div.muted', { style: 'font-size:12.5px' },
          missing(investedChange) || Math.abs(investedChange) < 1
            ? 'Your money in stayed level, so this is market movement.'
            : `— but you also paid ${investedChange > 0 ? 'in' : 'out'} ${inr(Math.abs(investedChange))} over the same period, so this is not your return. The tiles below are.`)),
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
  const dd = p.drawdown || {};
  const tone = (v) => (missing(v) ? 'neutral' : v > 0 ? 'positive' : v < 0 ? 'negative' : 'neutral');

  const benchmarkTiles = BENCHMARKS.map((b) => {
    const read = (p.benchmarks || []).find((x) => x.symbol === b.symbol) || {};
    const excess = p[b.excessField];
    return kpi({
      label: `Against the ${read.label || b.label}`,
      value: missing(excess) ? NOT_MEASURED : pp(excess),
      sub: missing(read.returnPercent) ? (read.coverage || 'no index data stored yet') : `index moved ${pct(read.returnPercent)}`,
      tone: tone(excess),
    });
  });

  return el('div.grid.kpis', { style: 'margin-top:12px' },
    kpi({
      label: `Time-weighted return, ${p.daysSpanned || 0} days`,
      value: missing(p.twrPercent) ? NOT_MEASURED : pct(p.twrPercent),
      sub: missing(p.twrAnnualisedPercent) ? 'deposits and withdrawals removed' : `${pct(p.twrAnnualisedPercent)} a year`,
      tone: tone(p.twrPercent),
    }),
    ...benchmarkTiles,
    kpi({
      label: 'Below your peak',
      value: missing(dd.currentDrawdownPercent) ? NOT_MEASURED : (dd.currentDrawdownPercent === 0 ? 'At a high' : pct(dd.currentDrawdownPercent)),
      sub: missing(dd.maxDrawdownPercent) ? '' : `deepest fall so far ${pct(dd.maxDrawdownPercent)}`,
      tone: dd.currentDrawdownPercent < -10 ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Cash at the broker',
      value: p.cash && !missing(p.cash.availableCash) ? inr(p.cash.availableCash) : NOT_MEASURED,
      sub: p.cash && !missing(p.cash.cashPercentOfTotal)
        ? `${pct(p.cash.cashPercentOfTotal, { signed: false })} of the book${p.cash.asOf ? `, ${shortDate(p.cash.asOf)}` : ''}`
        : (p.cash && p.cash.asOf ? `as of ${shortDate(p.cash.asOf)}` : 'captured at the 15:00 snapshot'),
      tone: 'neutral',
    }));
}

/**
 * What the time-weighted return could NOT account for.
 *
 * The section's own prose says these tiles "remove your deposits and withdrawals" - full stop -
 * while the payload says 11 of 24 flow days could not be corrected, and that 2 of 30 holdings
 * have no purchase lot on file. Both sentences were already written, in `caveats[]`, and simply
 * never rendered. A mandatory coverage line is the house rule on every other surface that makes
 * a measured claim (SPEC 41, 49.14, 50; Gotcha 44 and 115); this is the one place the coverage
 * line is promised in prose and then withheld.
 *
 * The caveat text also names its own fix (import the Zerodha tradebook), so showing it is the
 * nudge that closes the gap rather than a disclaimer that sits there for ever.
 */
function performanceCaveats(p) {
  const notes = (p && p.caveats) || [];
  if (!notes.length) return null;
  return el('div.info-box', { style: 'margin-top:12px' },
    el('b', {}, 'What these figures could not account for'),
    el('ul', { style: 'margin:6px 0 0;padding-left:18px' },
      ...notes.map((c) => el('li', { style: 'margin-top:3px' }, c))));
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
        el('div', { style: 'font-size:19px;font-weight:600' }, corr(a.informationCoefficient)))),
    sampleCaveat(a));
}

/**
 * What the pick count actually is (Gotcha 88, SPEC 38.10).
 *
 * The live cell reads 4,979 picks at 90 days and an IC of 0.114 - just above the conventional
 * 0.10 "useful signal" line - which together read as overwhelming evidence. They are not. That
 * figure is roughly 82 screening dates times ~300 stocks whose returns move together, measured
 * over windows that share most of their days, so the number of INDEPENDENT readings is nearer
 * five, and the measured edge sits at about t=1.24, p=0.28. A four-figure row count is the exact
 * quantity SPEC 38 says never to read as a sample size, and the display gate here is
 * MIN_SAMPLE_FOR_DISPLAY = 10 - a row count, so it cannot catch this.
 *
 * The numbers stay on screen: they are real, they are just not yet distinguishable from luck,
 * and saying so IS the discipline. Hiding them would be the opposite lesson.
 */
function sampleCaveat(a) {
  if (!a || missing(a.sampleSize)) return null;
  return el('div.info-box', { style: 'margin-top:10px;margin-bottom:0' },
    el('b', {}, 'Treat this as early, not proven.'),
    ' ',
    `Those ${num(a.sampleSize)} picks overlap heavily — the same stocks are scored again every `
    + 'screening day, over periods that share most of their days — so the number of genuinely '
    + 'independent readings behind these three figures is far smaller than the count suggests. '
    + 'On the evidence so far this edge is not yet distinguishable from luck. ',
    el('a', { href: 'accuracy.html' }, 'Track Record shows the working'),
    '.');
}

// ------------------------------------------------------------- top movers

function topMovers(live, asOf) {
  if (!live || live.length === 0) {
    return empty('No movement recorded',
      'Either the market has not moved your stocks, or prices have not synced yet.');
  }

  const sorted = [...live].sort((a, b) => b.dayChangePercent - a.dayChangePercent);
  const picks = [...sorted.slice(0, 3), ...sorted.slice(-3).reverse()]
    .filter((h, i, arr) => arr.findIndex((x) => x.symbol === h.symbol) === i);

  const chart = barChart(picks.map((h) => ({
    label: displaySymbol(h.symbol),
    value: h.dayChangePercent,
    // dayChangeValue, NOT dayChange: the stored field is per share, so this note told the
    // investor LCCPROJECT had moved Rs 27 on a day the position gained Rs 2,728 (B-120). The
    // percentage above it was right all along - a ratio is the same per share or per position.
    note: missing(h.dayChangeValue) ? NOT_MEASURED : inr(h.dayChangeValue),
  })), { width: 620, format: (v) => pct(v) });

  // The session is named in the card rather than in the section title: section() derives its fold
  // key from the title, so a title carrying a date would hand the reader a new key every morning
  // and forget their open/closed choice with it.
  return card(
    el('div.muted', { style: 'font-size:12.5px;margin-bottom:8px' },
      `Trading session of ${sessionPhrase(asOf)}${asOf.known && !asOf.today ? ' — the most recent prices the app holds' : ''}.`),
    chart || empty('No movement to chart', ''));
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
  const perf = perfRes.data;
  const asOf = priceAsOf();
  // Filtered once here and passed down rather than again inside topMovers, so the chart and the
  // folded heading can only ever describe the same set. Sorted by the SIZE of the move, so
  // movers[0] is the headline the folded heading shows.
  const movers = (holdingsRes.data || [])
    .filter((h) => Number.isFinite(h.dayChangePercent) && h.dayChangePercent !== 0)
    .sort((a, b) => Math.abs(b.dayChangePercent) - Math.abs(a.dayChangePercent));
  const twr = perf && !missing(perf.twrPercent) ? perf.twrPercent : null;
  const biggestMove = movers.length ? movers[0].dayChangePercent : null;
  const nodes = [];

  nodes.push(withCount(section('Where you stand',
    'A snapshot of everything you own right now. "Current value" is what your holdings are worth today; "total gain or loss" compares that against what you originally paid.',
    kpiRow(s && s.portfolio, asOf)), (s && s.portfolio && s.portfolio.holdingsCount) || 0));

  nodes.push(withSummary(section('Is your money growing?',
    'Your portfolio value day by day, against what you paid for it. The gap between the two lines is your profit — but that gap cannot move when you add or withdraw money, so the tiles below take your deposits and withdrawals out as far as the records allow, and set the result against two different indices. Missing days are days the app was not running; the line breaks rather than guessing.',
    gainVsGrowthNote(s && s.portfolio, perf),
    equityCurve(seriesRes.data),
    performanceStrip(perf),
    performanceCaveats(perf)),
    // The time-weighted return, not the headline gain on cost: the whole point of SPEC 46 is
    // that gain-on-cost cannot move when money comes in or out, so it read the same through
    // seven flat months. Missing stays missing — a folded heading is the last place a figure
    // nobody measured should be allowed to look measured.
    twr === null ? null : pct(twr),
    twr === null ? {} : { type: twr >= 0 ? 'success' : 'danger' }));

  nodes.push(withCount(section('What needs your attention today',
    'Everything the app thinks is worth a look, most urgent first: stocks flagged to exit, holdings whose original reason for buying is weakening, and drift from the allocation you set. These are prompts to review, never instructions to trade.',
    attentionList(s && s.attention)), (s && s.attention ? s.attention.length : 0)));

  const hhi = s && s.risk ? s.risk.hhiClassification : null;
  nodes.push(withSummary(section('Risk and track record',
    'On the left, how concentrated your portfolio is — HHI rises when your money is packed into fewer stocks or sectors. On the right, whether the app’s own past picks actually beat the index — and how far that can yet be trusted.',
    el('div.grid.two', {}, riskPanel(s && s.risk), accuracyPanel(s && s.accuracy))),
    hhi,
    // The bare enum reads as a grade on a scale nobody has seen: "LOW" beside a heading could be
    // a low score. Name the quantity it grades.
    hhi ? { label: `Concentration: ${humanLabel(hhi).toLowerCase()}` } : {}));

  nodes.push(withSummary(section('Biggest moves',
    `Your three best and three worst performers by percentage over ${sessionPhrase(asOf)}. Useful for spotting a surprise, not for making decisions — one day says nothing about a business you mean to own for years.`,
    topMovers(movers, asOf)),
    // The biggest move, NOT a count of movers. This section charts the best three and worst
    // three, so a count of everything that moved would be a number describing a list that is
    // not underneath it (Gotcha 98) — and "6" would be true every single day, which tells the
    // reader nothing about whether today is worth opening.
    biggestMove === null ? null : pct(biggestMove),
    biggestMove === null ? {} : { type: biggestMove >= 0 ? 'success' : 'danger' }));

  // Who else is covering what you own (SPEC 49.15). The Overview has no table to hang the
  // portfolio's Analysts column on, so the question is answered as three counts and a link
  // rather than being left off the landing page entirely. Deliberately NOT a second copy of
  // the full panel: a duplicate of a table one click away is noise on the surface where noise
  // costs most (B-121, B-134). Built on the same partition as that panel, so the number here
  // cannot disagree with the number there (B-098).
  const analyst = analystCoverageStrip(holdingsRes.data || []);
  if (analyst) {
    const coveredNow = (holdingsRes.data || []).filter((h) => h.analystHouses > 0).length;
    nodes.push(withCount(section('Who else is covering what you own',
      'How many of your holdings have a brokerage price target running on them right now. This '
      + 'is other people’s opinion, recorded so it can be scored later — it is not this app’s '
      + 'view and it changes no score here.',
      analyst), coveredNow));
  }

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
