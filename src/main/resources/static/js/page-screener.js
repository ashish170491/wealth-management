/**
 * Screener — the ~370-stock multibagger universe, sortable and filterable.
 *
 * Reads /api/dashboard/screener rather than /api/multibagger/scores, because the latter is
 * an in-memory cache that is empty after the daily restart, and /history defaults to today
 * which is empty until the 14:00 run. See DashboardService.screener().
 *
 * Rebuilt 2026-09-09 to put the business first. The table used to centre on seven score bars,
 * four of them price behaviour that B-023 records as collinear and near-zero IC, while ROCE,
 * debt, growth, promoter holding, valuation and red flags arrived on every row and were never
 * drawn. Those are now the default columns; the seven bars are one chip away ("Show the seven
 * scores"). No weight moved and no score changed — this is a display decision (SPEC 12.5, 27.2).
 *
 * Four of the dimension scores are nullable (B-019). A null means "could not be measured", NOT a
 * low score — so nulls render as the "not measured" marker in tables and as gaps in the radar,
 * never as 0. The same rule applies to every business-number cell (fundamentals-cells.js).
 */

import { get } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters, sectorOptions } from './filters.js';
import {
  inr, pct, num, humanLabel, displaySymbol, stockHref, missing, shortDate, daysAgo,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, badge, table, scoreBar, unmeasured, alert,
  withCount, withSummary,
} from './ui.js';
import { barChart, radar } from './charts.js';
import { loadWatchedSet, watchButton } from './watch-button.js';
import { buyTimingCell, buyTimingRank, entryPriceCell } from './buy-timing.js';
import { compoundingCell, compoundingRank } from './compounding.js';
import { macroExposureCol } from './macro-cells.js';
import { themeCol, themeFilterGroup, themeCoverageLine } from './theme-cells.js';
import {
  analystCoverageCol, analystFilterGroup, analystCoverageLine,
} from './analyst-cells.js';
import {
  finQualityCell, finQualityRank, roceCell, roceValue, leverageCell, leverageValue,
  growthCell, growthValue, promoterCell, promoterValue, valuationCell, valuationRank,
  redFlagsCell, redFlagsRank, worstFlag, marketCapCell, sectorCell, ownedLine,
} from './fundamentals-cells.js';

/** Symbols already on the watchlist — filled at boot so the "+ Watch" buttons know their state. */
let watched = new Set();

const view = document.getElementById('view');

/** The seven scoring dimensions, in the order the screener weights them. */
const DIMENSIONS = [
  { key: 'technicalMomentumScore', label: 'Momentum', short: 'Mom' },
  { key: 'volumeAccumulationScore', label: 'Volume', short: 'Vol' },
  { key: 'relativeStrengthScore', label: 'Rel. Strength', short: 'RS' },
  { key: 'priceStructureScore', label: 'Price Structure', short: 'Str' },
  { key: 'valuationScore', label: 'Valuation', short: 'Val' },
  { key: 'institutionalInterestScore', label: 'Institutions', short: 'Inst' },
  { key: 'financialQualityScore', label: 'Financial Quality', short: 'Fin' },
];

/**
 * Sector Tailwind was the eighth axis until 2026-09-03 (SPEC 39.2). It is gone from the
 * composite, and the column is null on every row screened since. Leaving it in this list drew a
 * permanently empty axis and made every stock read "7 of 8 measured" - presenting a DELETED
 * dimension as an UNMEASURED one, which is the null-is-not-zero rule (Gotcha 21) inverted.
 * Counts below derive from DIMENSIONS.length so this cannot drift again.
 */

/** The entry verdicts under which a compounder counts as "at a fair price" (SPEC 12.11 vocabulary). */
const FAIR_ENTRY = new Set(['BUY_NOW', 'ACCUMULATE']);

let rows = [];
let screeningDate = null;
/**
 * Which version of the scoring configuration produced these numbers (SPEC 38.1). Shown
 * because a "63" from one engine version and a "63" from another are different measurements:
 * a weight change or a flag flip rescales every score on this page at once, and twice in the
 * past it did so silently (B-018, B-019).
 */
let scoringVersion = null;
/**
 * The screener's chips, now declared against the shared bar (SPEC 27.13, filters.js). The
 * vocabulary is unchanged — what moved is the machinery, so every other screen gets the same
 * chips, counts and Clear control without a second implementation to keep in step.
 */
const FILTERS = chipFilters([
  // The shortlist first: it is the one chip that answers the page's question directly, and it is
  // a conjunction of verdicts the app already makes, never a new one (SPEC 20 rule 10).
  {
    key: 'shortlist',
    toggle: true,
    text: 'Shortlist: compounders at a fair price I do not own',
    title: 'Passed the compounding checks, entry reads "good entry" or "buy in tranches", and not already in your portfolio.',
    test: (r) => isCompounder(r) && atFairPrice(r) && !r.inHoldings,
  },
  {
    label: 'Size:',
    key: 'cap',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'SMALL_CAP', text: 'Small', test: (r) => r.marketCapCategory === 'SMALL_CAP' },
      { value: 'MID_CAP', text: 'Mid', test: (r) => r.marketCapCategory === 'MID_CAP' },
      { value: 'LARGE_CAP', text: 'Large', test: (r) => r.marketCapCategory === 'LARGE_CAP' },
    ],
  },
  {
    label: 'Grade:',
    key: 'grade',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'A', text: 'A only', test: (r) => String(r.grade || '').startsWith('A') },
      { value: 'B', text: 'B only', test: (r) => String(r.grade || '').startsWith('B') },
    ],
  },
  {
    label: 'Owned:',
    key: 'held',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'HELD', text: 'I own it', test: (r) => !!r.inHoldings },
      { value: 'NOT_HELD', text: 'I do not', test: (r) => !r.inHoldings },
    ],
  },
  {
    label: 'Compounding:',
    key: 'compounding',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'COMPOUNDER',
        text: 'Compounders only',
        // Deliberately excludes NOT_MEASURED: a business whose accounts could not be read has
        // not been judged, and must not be swept in beside ones that passed five real checks.
        test: isCompounder,
        title: 'Passed all five business checks. Businesses whose accounts could not be read are excluded — not judged is not the same as passed.',
      },
    ],
  },
  {
    label: 'Risk:',
    key: 'risk',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'CLEAN',
        text: 'Hide red flags',
        // Hides HIGH and MEDIUM and KEEPS the unchecked: a stock nobody has examined is not a
        // stock that passed (Gotcha 44), so it stays visible, striped.
        test: (r) => { const w = worstFlag(r); return w !== 'HIGH' && w !== 'MEDIUM'; },
        title: 'Hides stocks with a high or medium red flag. Stocks nobody has examined stay visible, marked "not measured".',
      },
      { value: 'UNCHECKED', text: 'Not yet checked', test: (r) => worstFlag(r) === null },
    ],
  },
  {
    label: 'Sector:',
    key: 'sector',
    ownLine: true,
    options: (rs) => sectorOptions(rs, { label: humanLabel }),
  },
  analystFilterGroup(),
  // Narrow to a government-funded theme (SPEC 51.5). A ROW filter, not a display toggle:
  // "show me the semiconductor names we screen" is the question the theme map exists for.
  themeFilterGroup,
  {
    key: 'showThemes',
    toggle: true,
    text: 'Show themes',
    title: 'Adds the Theme column: which government-funded themes name each business, and "—" '
      + 'for the ones none does. Off by default because this table is at its column budget, not '
      + 'because the tag is thin. A theme is a demand signal, never a quality signal, and it '
      + 'contributes zero points to any score.',
    // No `test` — like its neighbours it changes which COLUMNS are drawn and must never make
    // the bar report the table as filtered.
  },
  {
    key: 'showAnalysts',
    toggle: true,
    text: 'Show analyst targets',
    title: 'Adds the Analysts column: how many brokerages have a price target running on each '
      + 'stock, and the median target. Off by default because this table is already at its '
      + 'column budget, not because the data is thin — 213 of 274 screened stocks carry a live '
      + 'target. It contributes zero points to any score.',
    // No `test`, like the toggle below: it changes which COLUMNS are drawn, it does not narrow
    // the list, so it must never make the bar report the table as filtered.
  },
  {
    key: 'showScores',
    toggle: true,
    text: 'Show the seven scores',
    title: 'Adds one bar per scoring dimension. Off by default: four of the seven are price behaviour, and the entry question is already answered by the "Still good time to buy?" column.',
    // No `test` — this one changes which COLUMNS are drawn, it does not narrow the list, so it
    // must never make the bar report the table as filtered.
  },
  { search: true, placeholder: 'Search a stock or sector…' },
], (opts) => paint(opts));

// ------------------------------------------------------------------ filtering

function isCompounder(r) {
  return r.compounding === 'COMPOUNDER';
}

function atFairPrice(r) {
  return FAIR_ENTRY.has(r.buyTiming);
}

function visible() {
  return FILTERS.apply(rows);
}

// ---------------------------------------------------------------- main table

/**
 * The composite, with what used to be three columns folded under it: grade, percentile rank and
 * the 30-day change read against the universe (B-064). Two-line cell, SPEC 27.10 rule 5.
 */
function scoreCell(r) {
  const node = el('span', {});
  node.append(el('span.cell-main', { style: 'font-size:14px' }, num(r.compositeScore)));

  const meta = [];
  if (r.grade) meta.push(`grade ${r.grade}`);
  if (!missing(r.percentileRank)) meta.push(`top ${(100 - r.percentileRank).toFixed(0)}%`);
  if (meta.length) node.append(el('span.cell-sub', {}, meta.join(' · ')));

  const rel = r.scoreDelta30dRelative;
  const raw = r.scoreDelta30d;
  let tip = 'The 0-100 composite. ';
  if (!missing(rel)) {
    const arrow = rel > 0 ? '▲' : rel < 0 ? '▼' : '▶';
    const s = el('span.cell-sub.' + (rel > 0 ? 'pos' : rel < 0 ? 'neg' : 'muted'), {},
      `${arrow} ${rel > 0 ? '+' : ''}${rel} vs peers, 30d`);
    node.append(s);
    tip += `Since ${shortDate(r.scoreDelta30dFrom)} the score moved ${raw > 0 ? '+' : ''}${raw} while the typical `
      + `screened stock moved ${r.universeShift30d > 0 ? '+' : ''}${r.universeShift30d} (the universe shift), `
      + `so relative to peers it is ${rel > 0 ? '+' : ''}${rel}. That relative figure is the one to act on.`;
  } else if (!missing(raw)) {
    node.append(el('span.cell-sub.muted', {}, `${raw > 0 ? '▲ +' : raw < 0 ? '▼ ' : '▶ '}${raw} in 30d`));
    tip += `Since ${shortDate(r.scoreDelta30dFrom)} the score moved ${raw > 0 ? '+' : ''}${raw}. The universe shift could not `
      + 'be measured (fewer than 30 stocks screened on both dates), so this is the raw move.';
  } else {
    tip += 'No screening run from about 30 days ago to compare against.';
  }
  node.title = tip;
  return node;
}

function scoreTable(data) {
  if (data.length === 0) {
    return empty('No stocks match these filters', 'Try widening the filters above, or clearing the search.');
  }

  const cols = [
    {
      key: 'symbol',
      label: 'Stock',
      render: (r) => el('span', {},
        el('a', { href: stockHref(r.symbol), title: r.symbol }, displaySymbol(r.symbol)),
        ownedLine(r)),
    },
    { key: 'watch', label: '', sortable: false, render: (r) => watchButton(r.symbol, watched, { note: `From screener: score ${r.compositeScore}${r.grade ? ', grade ' + r.grade : ''}` }) },
    { key: 'compositeScore', label: 'Score', align: 'r', render: scoreCell },
    { key: 'verdict', label: 'Verdict', render: (r) => badge(r.verdict) },
    // "Can this business compound?" (SPEC 41). A gate badge, not a score: it never enters the
    // composite, and it answers the long-horizon question the composite is worst at, since 59%
    // of that weight is price behaviour. Sorts compounders first, unmeasured last.
    {
      key: 'compounding',
      // Two words on purpose: a header wraps at spaces, so its minimum width is its longest
      // WORD, not the whole label (SPEC 27.10).
      label: 'Can it compound?',
      value: compoundingRank,
      render: compoundingCell,
    },
    // "Is the weather against this business right now?" (SPEC 48). A separate question from
    // the gate above and from the timing verdict below, and deliberately not blended into
    // either: a headwind is background for reading the next results, not a score.
    macroExposureCol(),
    // Quality and entry timing are separate columns on purpose: a good business at a stretched
    // price and a weak business at a fair price are different situations, and one blended number
    // would hide which is which. Sorts best-entry-first, unmeasured last (SPEC 12.11).
    {
      key: 'buyTiming',
      label: 'Still good time to buy?',
      value: buyTimingRank,
      render: buyTimingCell,
    },
    // Sits immediately after the verdict because it is conditioned on it (SPEC 12.12).
    {
      key: 'suggestedEntryPrice',
      label: 'How to buy',
      align: 'r',
      value: (r) => r.suggestedEntryPrice,
      render: entryPriceCell,
    },
  ];

  // Opt-in (SPEC 49.15): who else is quoting a target. Same renderer as the portfolio, the
  // watchlist, discovery and the stock page — one cell, nothing to drift (Gotcha 85). Gated on
  // the toggle purely for width: the default table is at the 20-column budget B-098 set.
  if (FILTERS.state.showAnalysts) {
    cols.push(analystCoverageCol());
  }

  // Opt-in (SPEC 51.5), same renderer as the portfolio, watchlist, discovery and the Themes
  // page. Gated purely for width, exactly like the column above.
  if (FILTERS.state.showThemes) {
    cols.push(themeCol());
  }

  // Opt-in: one compact bar per dimension. Nulls become the striped "not measured" marker, which
  // is deliberately impossible to mistake for a low bar.
  if (FILTERS.state.showScores) {
    for (const d of DIMENSIONS) {
      cols.push({
        key: d.key,
        label: d.short,
        align: 'r',
        render: (r) => scoreBar(r[d.key], { width: 44 }),
      });
    }
  }

  // The business. Each of these is a raw figure or a server-side verdict — none is a second
  // score, and the "Can it compound?" gate above is the only summary of them (Gotcha 85).
  cols.push(
    { key: 'financialQualityVerdict', label: 'Fin. quality', value: finQualityRank, render: finQualityCell },
    { key: 'rocePercent', label: 'ROCE', align: 'r', value: roceValue, render: roceCell },
    { key: 'debtToEquity', label: 'Debt / equity', align: 'r', value: leverageValue, render: leverageCell },
    { key: 'yoyProfitGrowth', label: 'Profit growth (YoY)', align: 'r', value: growthValue, render: growthCell },
    { key: 'promoterHoldingPct', label: 'Promoter holding', align: 'r', value: promoterValue, render: promoterCell },
    { key: 'dcfVerdict', label: 'Valuation', value: valuationRank, render: valuationCell },
    { key: 'redFlags', label: 'Red flags', value: redFlagsRank, render: redFlagsCell },
    // Under-discovery and buyability are NOT dimensions — neither enters the composite.
    {
      key: 'underDiscoveryScore',
      label: 'Under-radar',
      align: 'r',
      render: (r) => scoreBar(r.underDiscoveryScore, { width: 44 }),
    },
    {
      key: 'liquidityTier',
      label: 'Buyable',
      render: (r) => {
        if (!r.liquidityTier || r.liquidityTier === 'UNKNOWN') {
          return unmeasured('Liquidity could not be measured for this stock');
        }
        const type = r.liquidityTier === 'LIQUID' ? 'success'
          : r.liquidityTier === 'MODERATE' ? 'warning' : 'danger';
        // Explicit labels: humanLabel(MODERATE) is "Moderately Concentrated" from the HHI
        // vocabulary, which is wrong here and set this column's floor at 105 px.
        const label = { LIQUID: 'Liquid', MODERATE: 'Moderate', THIN: 'Thin' }[r.liquidityTier]
          || humanLabel(r.liquidityTier);
        const node = badge(r.liquidityTier, { type, label });
        node.title = r.liquidityTier === 'THIN'
          ? 'Too little is traded daily to build or exit a position at a fair price.'
          : r.liquidityTier === 'MODERATE' ? 'Buildable in tranches; a single large order would move the price.'
            : 'Enough traded daily to build or exit a position without moving the price.';
        return node;
      },
    },
    { key: 'currentPrice', label: 'Price', align: 'r', render: (r) => inr(r.currentPrice, { abbreviate: false }) },
    { key: 'marketCapCrores', label: 'Market cap', align: 'r', render: marketCapCell },
    { key: 'sector', label: 'Sector', value: (r) => (r.sector ? humanLabel(r.sector) : null), render: sectorCell });

  // filter:false — this page's search lives in the chip bar above, with the chips it works
  // alongside. Two search boxes on one screen is the same one-question-two-controls confusion
  // that "Refresh" and "Refresh from NSE now" caused on the IPO page (Gotcha 85).
  return table(cols, data, { sortKey: 'compositeScore', filter: false });
}

// ------------------------------------------------------------- distributions

function gradeDistribution(data) {
  const order = ['A+', 'A', 'B+', 'B', 'C+', 'C', 'D'];
  const colors = { 'A+': '#1b5e20', A: '#2e7d32', 'B+': '#1976d2', B: '#42a5f5', 'C+': '#f57c00', C: '#ef6c00', D: '#c62828' };
  const items = order
    .map((g) => ({ label: g, value: data.filter((r) => r.grade === g).length, color: colors[g] }))
    .filter((i) => i.value > 0);

  if (items.length === 0) return empty('No grades recorded', '');
  return card(barChart(items, { width: 620, labelWidth: 70, format: (v) => String(Math.round(v)) }));
}

/**
 * Average of each dimension across the filtered set, with an honest coverage count.
 *
 * The coverage number is the interesting part: a dimension measured for only 60 of 288
 * stocks tells you the screener is largely blind on it, which the average alone hides.
 */
function dimensionCoverage(data) {
  const items = DIMENSIONS.map((d) => {
    const vals = data.map((r) => r[d.key]).filter((v) => Number.isFinite(v));
    return {
      label: d.label,
      value: vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : NaN,
      note: `measured for ${vals.length} of ${data.length}`,
    };
  });

  const chart = barChart(items.filter((i) => Number.isFinite(i.value)), {
    width: 700, labelWidth: 150, format: (v) => v.toFixed(0),
  });

  const blind = items.filter((i) => !Number.isFinite(i.value));
  const partial = DIMENSIONS.map((d) => {
    const n = data.filter((r) => Number.isFinite(r[d.key])).length;
    return { label: d.label, n };
  }).filter((x) => x.n > 0 && x.n < data.length * 0.9);

  // The business columns get the same treatment: a column that is mostly "not measured" is a
  // column the reader should not sort by yet.
  const business = [
    ['ROCE', (r) => !missing(r.rocePercent) || !missing(r.roaPercent)],
    ['Debt / equity', (r) => !missing(r.debtToEquity)],
    ['Profit growth', (r) => !missing(r.yoyProfitGrowth)],
    ['Promoter holding', (r) => !missing(r.promoterHoldingPct)],
    ['Valuation', (r) => !missing(r.peDeviation) || ['DEEPLY_UNDERVALUED', 'UNDERVALUED', 'FAIRLY_VALUED', 'EXPENSIVE', 'EXTREMELY_EXPENSIVE'].includes(String(r.dcfVerdict || '').toUpperCase())],
    ['Red flags checked', (r) => r.forensicFlags !== null && r.forensicFlags !== undefined],
    ['Sector', (r) => !!r.sector],
  ].map(([label, has]) => `${label} ${data.filter(has).length}/${data.length}`);

  return card(
    chart || empty('No dimension scores available', ''),
    partial.length
      ? el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
        'Partly measured: ' + partial.map((p) => `${p.label} (${p.n}/${data.length})`).join(', ')
        + '. Stocks the screener could not measure are excluded from that average rather than counted as zero.')
      : null,
    blind.length
      ? el('div.muted', { style: 'font-size:12.5px;margin-top:6px' },
        'Not measured at all in this run: ' + blind.map((b) => b.label).join(', '))
      : null,
    el('div.muted', { style: 'font-size:12.5px;margin-top:6px' },
      'Business columns measured: ' + business.join(' · ') + '. Promoter holding and growth are stored from the 2026-09-09 screening onwards, so an older run reads "not measured" for both.'));
}

/** Does a higher score actually go with a better return on stocks you own? */
function scoreVsReturn(data) {
  const owned = data.filter((r) => r.inHoldings && Number.isFinite(r.holdingsPnlPercent) && Number.isFinite(r.compositeScore));
  if (owned.length < 3) {
    return empty('Not enough owned-and-scored stocks yet',
      `This compares the score against your actual return, and needs at least 3 stocks that are both owned and screened. Right now there ${owned.length === 1 ? 'is' : 'are'} ${owned.length}.`);
  }

  const items = [...owned]
    .sort((a, b) => b.compositeScore - a.compositeScore)
    .map((r) => ({
      label: `${displaySymbol(r.symbol)} (${r.compositeScore})`,
      value: r.holdingsPnlPercent,
      note: `score ${r.compositeScore}, grade ${r.grade || '?'}`,
    }));

  // Rank correlation between score and realized return, computed here only because it is a
  // property of the displayed selection, not a portfolio statistic the backend owns.
  const byScore = [...owned].sort((a, b) => a.compositeScore - b.compositeScore);
  const byReturn = [...owned].sort((a, b) => a.holdingsPnlPercent - b.holdingsPnlPercent);
  const n = owned.length;
  let sumD2 = 0;
  for (const r of owned) {
    const d = byScore.indexOf(r) - byReturn.indexOf(r);
    sumD2 += d * d;
  }
  const rho = 1 - (6 * sumD2) / (n * (n * n - 1));

  return el('div', {},
    el('div.alert.' + (rho > 0.2 ? 'success' : rho < -0.2 ? 'warning' : 'info'), {},
      el('div.alert-title', {}, `Rank agreement: ${rho >= 0 ? '+' : ''}${rho.toFixed(2)}`),
      el('div', {}, rho > 0.2
        ? 'Higher-scored stocks you own have generally done better. The score is earning its keep here.'
        : rho < -0.2
          ? 'Higher-scored stocks you own have generally done worse. Worth treating the score with caution on your own holdings.'
          : 'Little relationship between the score and your returns on these stocks — expected with a small sample and a short holding period.'),
      el('div.meta', {}, `Based on ${n} stocks you own that were also screened. This is your portfolio only, not the screener’s full track record — see Track Record for that.`)),
    card(barChart(items, { width: 760, labelWidth: 190, format: (v) => pct(v) })));
}

// ------------------------------------------------------------------ top card

function pickCard(p, { owned = false } = {}) {
  const dims = DIMENSIONS.map((d) => ({ label: d.short, value: Number.isFinite(p[d.key]) ? p[d.key] : null }));
  const measured = dims.filter((d) => d.value !== null).length;

  const facts = el('div.muted', { style: 'font-size:12px;margin:4px 0 6px;line-height:1.5' });
  const bits = [];
  if (!missing(p.rocePercent)) bits.push(`ROCE ${pct(p.rocePercent, { signed: false, digits: 0 })}`);
  else if (!missing(p.roaPercent)) bits.push(`ROA ${pct(p.roaPercent, { signed: false })}`);
  if (!missing(p.debtToEquity)) bits.push(`D/E ${num(p.debtToEquity, 2)}`);
  if (!missing(p.yoyProfitGrowth)) bits.push(`profit ${pct(p.yoyProfitGrowth, { digits: 0 })} YoY`);
  if (!missing(p.promoterHoldingPct)) bits.push(`promoter ${pct(p.promoterHoldingPct, { signed: false, digits: 0 })}`);
  facts.textContent = bits.length ? bits.join(' · ') : 'Business figures not measured for this stock.';

  return card(
    el('div.row.between', {},
      el('a', { href: stockHref(p.symbol), style: 'font-weight:600;font-size:15px;color:var(--navy-light);text-decoration:none' }, displaySymbol(p.symbol)),
      el('div.row', { style: 'gap:6px' }, compoundingCell(p), buyTimingCell(p))),
    el('div.row', { style: 'gap:10px;margin:6px 0 2px' },
      el('span', { style: 'font-size:22px;font-weight:600' }, num(p.compositeScore)),
      el('span.muted', { style: 'font-size:12.5px' }, `grade ${p.grade || '?'} · ${humanLabel(p.marketCapCategory)}${p.sector ? ' · ' + humanLabel(p.sector) : ''}`),
      owned ? badge('BUY', { type: 'success', label: 'You own it' }) : null),
    facts,
    radar(dims, { size: 235 }) || el('div.muted', {}, 'Too few measured dimensions to chart'),
    el('div.muted', { style: 'font-size:11.5px;text-align:center' },
      `${measured} of ${DIMENSIONS.length} dimensions measured${measured < DIMENSIONS.length ? ' — unmeasured axes are left out of the shape' : ''}`));
}

/**
 * The headline used to be "top six by composite" — a list 59% of whose ordering is price
 * behaviour. It is now the funnel this app exists for: passed the compounding checks AND the
 * entry reads fair today, ordered by composite. The counts are printed so an empty or short list
 * reads as a finding ("only 22 of 288 compound") rather than as a broken panel.
 */
function headline(data) {
  const compounders = data.filter(isCompounder);
  const fair = compounders.filter(atFairPrice).sort((a, b) => (b.compositeScore || 0) - (a.compositeScore || 0));
  const ownedCount = fair.filter((r) => r.inHoldings).length;

  const funnel = el('div.muted', { style: 'font-size:13px;margin-bottom:12px' },
    `${compounders.length} of ${data.length} pass the compounding checks; ${fair.length} of those read "good entry" or `
    + `"buy in tranches" today${ownedCount ? `, ${ownedCount} of which you already own` : ''}. `
    + 'Stocks whose accounts could not be read are not counted on either side.');

  if (fair.length === 0) {
    const top = [...data].sort((a, b) => (b.compositeScore || 0) - (a.compositeScore || 0)).slice(0, 6);
    return el('div', {}, funnel,
      alert({
        severity: 'INFO',
        title: 'No compounder is at a fair entry right now',
        message: 'That is a finding, not an error: the businesses that pass every check are running hot or are flagged. Below are the six highest composites instead — a composite is a reason to look closer, not a reason to buy.',
      }),
      el('div.grid.three', {}, ...top.map((p) => pickCard(p, { owned: p.inHoldings }))));
  }

  const grid = el('div.grid.three');
  for (const p of fair.slice(0, 6)) grid.append(pickCard(p, { owned: p.inHoldings }));
  return el('div', {}, funnel, grid);
}

// -------------------------------------------------------------------- paint

function paint(opts = {}) {
  const data = visible();
  const nodes = [];

  const age = daysAgo(screeningDate);
  const stale = age !== null && age > 2;
  const compounders = rows.filter(isCompounder).length;
  const fairCompounders = rows.filter((r) => isCompounder(r) && atFairPrice(r)).length;
  // The commonest grade in this run - one word for whether the universe is strong today.
  const gradeTally = new Map();
  for (const r of data) if (r.grade) gradeTally.set(r.grade, (gradeTally.get(r.grade) || 0) + 1);
  const topGrade = gradeTally.size
    ? [...gradeTally.entries()].sort((a, b) => b[1] - a[1])[0][0] : null;

  nodes.push(withCount(section('The screening universe',
    'Every stock the app scores out of 100 across seven dimensions — momentum, volume, relative strength, price structure, valuation, institutional interest and financial quality. A high score is a reason to look closer, not a reason to buy: the business numbers beside it are what to read next.',
    el('div', {},
      screeningDate
        ? el('div' + (stale ? '.alert.warning' : '.muted'), { style: stale ? '' : 'font-size:13px;margin-bottom:12px' },
          stale
            ? el('div', {}, el('div.alert-title', {}, `These scores are from ${shortDate(screeningDate)} — ${age} days old`),
              el('div', {}, 'The screening runs at 2:00 PM on weekdays. Prices have moved since these were calculated.'))
            : `Scores from ${shortDate(screeningDate)} · ${rows.length} stocks screened`)
        : alert({ severity: 'WARNING', title: 'No screening data found', message: 'The screener has not stored a run yet, so there is nothing to show.' }),
      scoringVersion
        ? el('div.muted', { style: 'font-size:12px;margin-bottom:12px' },
          'Scoring engine ',
          el('code', { title: 'Identifies the exact weights and settings that produced these scores. If this changes, scores from before and after are not directly comparable.' }, scoringVersion))
        : null,
      el('div.grid.kpis', { style: 'margin-bottom:16px' },
        kpi({ label: 'Stocks Screened', value: num(rows.length), tone: 'neutral' }),
        kpi({ label: 'Strong Candidates', value: num(rows.filter((r) => r.verdict === 'STRONG_MULTIBAGGER').length), tone: 'positive', sub: 'composite 80 or more' }),
        kpi({ label: 'Compounders', value: num(compounders), tone: 'positive', sub: 'pass the five business checks' }),
        kpi({ label: 'At a Fair Price', value: num(fairCompounders), tone: fairCompounders > 0 ? 'positive' : 'neutral', sub: 'compounders with a reasonable entry' }),
        kpi({ label: 'You Own', value: num(rows.filter((r) => r.inHoldings).length), tone: 'neutral' })))), rows.length));

  nodes.push(withCount(section('Compounders at a fair price',
    'The businesses that passed the five compounding checks — return on capital, real cash, self-funded growth, steady earnings, margins — and whose entry does not look stretched today. Each card shows the business figures first and the seven-dimension shape second. Dimensions the app could not measure are left out of the shape rather than drawn as zero.',
    headline(data)), fairCompounders));

  nodes.push(withCount(section('All screened stocks',
    'Sort by any column, or filter by sector, size, grade, risk and whether you own it. The default columns are the business: quality, return on capital, debt, growth, promoter holding, valuation and red flags. The seven scoring dimensions are one chip away. A striped marker means that figure could not be measured for that stock — it is never a zero.',
    FILTERS.bar(rows, data.length, 'stocks'),
    // The FILTERED count, because that is the list directly underneath this heading. The chip on
    // "The screening universe" above carries the unfiltered total, so both numbers are on screen
    // and each one describes its own list (Gotcha 98).
    scoreTable(data),
    // Only when the column is on: a line explaining what blanks in a column mean is noise when
    // the column is not drawn. Over `data` - the list actually under this heading (B-098).
    FILTERS.state.showAnalysts ? analystCoverageLine(data, { noun: 'stocks' }) : null,
    // Mandatory beside the Theme column (Gotcha 44): a column of dashes must not read as
    // "none of these are in a funded theme" when the map is hand-kept and may simply not
    // name them. Over the shown rows, so the count describes the list beneath it (B-098).
    FILTERS.state.showThemes ? themeCoverageLine(data, 'screened stocks') : null),
  data.length));

  nodes.push(withSummary(section('Grade spread',
    'How the screened universe breaks down by grade. A+ is a composite of 85 or more, D is under 35.',
    gradeDistribution(data)), topGrade, { type: 'info' }));

  nodes.push(withCount(section('Which dimensions can the app actually measure?',
    'Average score per dimension, plus how many stocks each one could be measured for. Coverage matters as much as the average: a dimension measured for a fraction of the universe is one the screener is mostly blind on. The same count is given for each business column.',
    dimensionCoverage(data)), DIMENSIONS.length));

  nodes.push(withCount(section('Does the score predict your own returns?',
    'For stocks you both own and the app screens, this lines the score up against your actual return. Rank agreement near zero means the two are unrelated on this sample.',
    // The SAMPLE, not a correlation. A rank agreement computed on a handful of stocks is the
    // kind of figure this app exists not to quote without its n beside it (SPEC 38), and folded
    // there is no room for both.
    scoreVsReturn(rows)), rows.filter((r) => r.inHoldings && r.compositeScore != null).length));

  mount(view, nodes);

  // Typing in the search box re-paints the page; the box must not lose the cursor each keystroke.
  if (opts.keepFocus) {
    const box = view.querySelector('input[type="search"]');
    if (box) {
      box.focus();
      box.setSelectionRange(box.value.length, box.value.length);
    }
  }
}

// --------------------------------------------------------------------- boot

async function boot() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading the screener…'), skeleton(4)));
  await initChrome();

  const [res, watchedSet] = await Promise.all([
    get('/api/dashboard/screener', { fallback: { screeningDate: null, scores: [] } })
      .catch(() => ({ data: { screeningDate: null, scores: [] } })),
    loadWatchedSet(), // DB-only (SPEC 37), verified by hand
  ]);
  watched = watchedSet;

  rows = (res.data && res.data.scores) || [];
  screeningDate = res.data && res.data.screeningDate;
  // Every row of a run carries the same stamp; a null means the run predates versioning.
  scoringVersion = (rows[0] && rows[0].scoringVersion) || null;
  paint();
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load the screener', String(err && err.message ? err.message : err))));
});
