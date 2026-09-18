/**
 * Discovery — the early-stage side of the system: which good businesses nobody has noticed
 * yet, whether you could actually buy them, who is buying them from the inside, and which
 * stocks the screener found for itself outside its curated list.
 *
 * Every screen in this dashboard is read-only, but this one has an extra rule: almost
 * nothing here has a measured track record. Under-discovery, insider pulse and the dynamic
 * universe all shipped in 2026-08 and none has forward returns yet. The page says so, in
 * each section, rather than presenting new numbers with the same confidence as the ones
 * that have been scored for months.
 *
 * Null discipline (SPEC 21 rule 7 / Gotcha 21) matters more here than anywhere else,
 * because most of this data is genuinely sparse: an insider verdict of null means "nobody
 * filed anything", NOT "insiders were neutral", and an under-discovery score of null means
 * the stock failed the quality gate or its ownership data was missing. Neither renders as 0.
 */

import { get } from './api.js';
import { loadWatchedSet, watchButton } from './watch-button.js';

/** Symbols already on the watchlist — filled at boot so every "+ Watch" button knows its state. */
let watched = new Set();
const watchCol = (why) => ({ key: 'watch', label: '', sortable: false, render: (r) => watchButton(r.symbol, watched, { note: why }) });

/**
 * symbol -> the screening row carrying its entry verdict (SPEC 12.11), filled once per page load.
 *
 * Sections other than Under-the-Radar list rows from different feeds - the insider ledger, the
 * expansion funnel - which have no verdict of their own. They are joined to the screening run by
 * symbol. A stock the screener has never looked at gets the striped "not measured" marker with
 * that reason, never a blank cell and never a neutral verdict: "we have not judged this" and "we
 * judged it and it was unremarkable" are different statements (Gotcha 21).
 */
let timing = new Map();

const NOT_SCREENED = {
  buyTiming: 'NOT_MEASURED',
  buyTimingReason: 'This stock was not in the last screening run, so its entry has not been judged.',
  suggestedEntryPrice: null,
  suggestedEntryRungs: [],
  suggestedEntryReason: 'No screening row, so there are no measured levels to build an entry plan from.',
};

/**
 * One line saying how much of a table the entry column could actually judge.
 *
 * Most rows on this page are, by construction, stocks the screener has NOT scored - that is what
 * makes them discoveries. The verdict needs a screening row (weekly RSI, distance from the
 * 52-week high), so the honest coverage here is low: measured 2026-08-28, 2 of 78 funnel rows and
 * 29 of 430 disclosures. Stating that is the difference between a column that looks broken and a
 * column that is reporting a real limit.
 */
function timingCoverage(rows) {
  const total = (rows || []).length;
  if (!total) return null;
  const judged = rows.filter((r) => timing.has(r.symbol)).length;
  if (judged === total) return null;
  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `Entry timing judged for ${judged} of ${total}. The rest are not in the screening universe `
    + 'yet, so there is no momentum or price-range data to judge an entry on — shown as '
    + 'not measured rather than guessed.');
}

const entryCol = () => ({
  key: 'suggestedEntryPrice',
  label: 'How to buy',
  align: 'r',
  value: (r) => (timing.get(r.symbol) || {}).suggestedEntryPrice,
  render: (r) => entryPriceCell(timing.get(r.symbol) || NOT_SCREENED),
});

const timingCol = () => ({
  key: 'buyTiming',
  label: 'Still good time to buy?',
  // Sorts on the joined verdict, best entry first, with unmeasured last in either direction -
  // the same ordering the screener uses, so the column behaves identically on both screens.
  value: (r) => buyTimingRank(timing.get(r.symbol) || NOT_SCREENED),
  render: (r) => buyTimingCell(timing.get(r.symbol) || NOT_SCREENED),
});
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters, sectorOptions } from './filters.js';
import {
  inr, num, pct, displaySymbol, stockHref, missing, shortDate, humanLabel,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, badge, table, scoreBar, unmeasured, alert, withCount,
  collapse,
} from './ui.js';
import { buyTimingCell, buyTimingRank, entryPriceCell } from './buy-timing.js';
// The business figures, drawn by the same renderers the screener uses (SPEC 12.5, B-098). Every
// one reads a field already on the screening row this page fetches; none of them decides anything.
import {
  finQualityCell, finQualityRank, roceCell, roceValue, leverageCell, leverageValue,
  growthCell, growthValue, valuationCell, valuationRank, redFlagsCell, redFlagsRank,
  marketCapCell, sectorCell, ownedLine, worstFlag,
} from './fundamentals-cells.js';
import { compoundingCell, compoundingRank } from './compounding.js';
import { macroExposureCol } from './macro-cells.js';
import { analyseCol } from './ipo-analyse.js';

const view = document.getElementById('view');

/** Composite a stock must clear before "under the radar" means anything (SPEC 12.10). */
const QUALITY_FLOOR = 65;
const UNDER_RADAR_FLOOR = 60;

// ------------------------------------------------------------- shared columns

/**
 * The stock cell, with "you own" underneath.
 *
 * A discovery page that re-pitches something already in the portfolio is wasting the reader's
 * attention on a decision they have already made. `inHoldings` was on every row and unused.
 */
const symbolCol = () => ({
  key: 'symbol',
  label: 'Stock',
  render: (r) => el('span', {},
    el('a', { href: stockHref(r.symbol), title: r.symbol }, displaySymbol(r.symbol)),
    ownedLine(r)),
});

/**
 * The composite with its grade, percentile and 30-day move **relative to the universe**.
 *
 * The relative figure is the one that means anything: the composite is re-scaled by every scoring
 * change and by every broad market move, so between two runs the whole universe can slide and
 * every stock reads as decaying on its own history (B-064).
 */
function scoreCell(r) {
  const node = el('span', {});
  node.append(el('span.cell-main', { style: 'font-size:14px' }, num(r.compositeScore)));

  const meta = [];
  if (r.grade) meta.push(`grade ${r.grade}`);
  if (!missing(r.percentileRank)) meta.push(`top ${(100 - r.percentileRank).toFixed(0)}%`);
  if (meta.length) node.append(el('span.cell-sub', {}, meta.join(' · ')));

  const rel = r.scoreDelta30dRelative;
  let tip = 'The 0-100 composite. Note that 59% of its weight is price behaviour, so a good '
    + 'business that has fallen scores low partly because it has fallen.';
  if (!missing(rel)) {
    node.append(el('span.cell-sub.' + (rel > 0 ? 'pos' : rel < 0 ? 'neg' : 'muted'), {},
      `${rel > 0 ? '▲ +' : rel < 0 ? '▼ ' : '▶ '}${rel} vs peers, 30d`));
    tip += ` Since ${shortDate(r.scoreDelta30dFrom)} it moved ${r.scoreDelta30d > 0 ? '+' : ''}${r.scoreDelta30d} `
      + `while the typical screened stock moved ${r.universeShift30d > 0 ? '+' : ''}${r.universeShift30d}.`;
  }
  node.title = tip;
  return node;
}

/** "Can this business compound?" (SPEC 41) — a gate, never a score. */
const compoundingCol = () => ({
  key: 'compounding', label: 'Can it compound?', value: compoundingRank, render: compoundingCell,
});

/**
 * Macro exposure, on the two lanes built from screening rows only.
 *
 * The insider, expansion and recent-listing tables are deliberately left without it: their rows
 * are not screening rows and carry none of these fields, so the column would draw "not measured"
 * on every line — spending column budget to say nothing, and making a working table look broken
 * (SPEC 27.16, Gotcha 120).
 */
const macroCol = () => macroExposureCol();

/**
 * The business figures themselves, in the order an investor reads them: what it earns on its
 * capital, what it owes, whether it is growing, and what is held against it.
 *
 * Labels are the screener's, character for character, and deliberately so. A column that reads
 * "ROCE" on one screen and "Return on capital" on the next invites the reader to wonder whether
 * they are the same measurement — the vocabulary discipline of Gotcha 85 applied to a column
 * heading rather than a verdict. It also keeps headers to one or two words, which is what actually
 * sets a column's minimum width (Gotcha 89): "Return on capital" wrapped to three lines here.
 */
const businessCols = () => [
  { key: 'rocePercent', label: 'ROCE', align: 'r', value: roceValue, render: roceCell },
  { key: 'debtToEquity', label: 'Debt / equity', align: 'r', value: leverageValue, render: leverageCell },
  { key: 'yoyProfitGrowth', label: 'Profit growth (YoY)', align: 'r', value: growthValue, render: growthCell },
  { key: 'redFlags', label: 'Red flags', value: redFlagsRank, render: redFlagsCell },
];

const sectorCol = () => ({ key: 'sector', label: 'Sector', value: (r) => r.sector || '', render: sectorCell });

// ------------------------------------------------------------------ buyability

/**
 * Buyability as the number that actually matters: how many trading days it would take to
 * build a 1 lakh position without being more than 10% of the day's volume.
 *
 * Returns null (not 0) when liquidity is unknown, so the caller renders the unmeasured
 * marker instead of "0 days", which would read as "instant".
 */
function daysToBuild(adv20, positionValue = 100000, participation = 0.10) {
  if (missing(adv20) || adv20 <= 0) return null;
  return Math.ceil(positionValue / (adv20 * participation));
}

function buyabilityCell(row) {
  const tier = row.liquidityTier;
  if (!tier || tier === 'UNKNOWN') return unmeasured('Liquidity could not be measured for this stock');

  const type = tier === 'LIQUID' ? 'success' : tier === 'MODERATE' ? 'warning' : 'danger';
  const parts = [badge(tier, { type, label: humanLabel(tier) })];

  const days = daysToBuild(row.liquidityAdv20d);
  if (days !== null) {
    parts.push(el('div.faint', { style: 'font-size:11.5px;margin-top:2px' },
      `~${days} ${days === 1 ? 'day' : 'days'} to build ${inr(100000)}`));
  }
  if (!missing(row.circuitDaysLast60) && row.circuitDaysLast60 >= 3) {
    parts.push(el('div.neg', { style: 'font-size:11.5px' },
      `circuit risk (${row.circuitDaysLast60}/60 d)`));
  }
  return el('div', {}, ...parts);
}

// ------------------------------------------------------------- under the radar

function underRadarSection(rows) {
  const scored = rows.filter((r) => !missing(r.underDiscoveryScore));
  const notMeasured = rows.length - scored.length;

  const picks = scored
    .filter((r) => r.compositeScore >= QUALITY_FLOOR && r.underDiscoveryScore >= UNDER_RADAR_FLOOR)
    .sort((a, b) => b.underDiscoveryScore - a.underDiscoveryScore);

  const explain = 'These stocks clear the quality bar AND still look undiscovered: small, '
    + 'barely owned by big institutions, and quietly being accumulated. The idea is that when '
    + 'large investors do arrive, their buying re-rates a small company much further than a big '
    + 'one. Check buyability before acting — a stock you cannot accumulate without moving the '
    + 'price is not an opportunity for you, whatever it scores. This ranking is new and has no '
    + 'measured track record yet.';

  const cols = [
    symbolCol(),
    watchCol('From discovery: under-the-radar candidate'),
    { key: 'compositeScore', label: 'Score', align: 'r', render: scoreCell },
    compoundingCol(),
    macroCol(),
    { key: 'underDiscoveryScore', label: 'Under-radar', align: 'r', render: (r) => scoreBar(r.underDiscoveryScore, { width: 54 }) },
    ...businessCols(),
    { key: 'marketCapCrores', label: 'Market cap', align: 'r', render: marketCapCell },
    { key: 'liquidityAdv20d', label: 'Buyability', render: buyabilityCell },
    timingCol(),
    entryCol(),
    { key: 'insiderPulseVerdict', label: 'Insiders', render: (r) => (r.insiderPulseVerdict ? badge(r.insiderPulseVerdict) : unmeasured('No insider filings on record for this stock')) },
    sectorCol(),
  ];

  const body = picks.length
    ? table(cols, picks, { sortKey: 'underDiscoveryScore', filter: false })
    : empty('Nothing under the radar in this run',
      `No stock cleared both a ${QUALITY_FLOOR} composite and an under-discovery score of `
      + `${UNDER_RADAR_FLOOR}. That is the expected result for a curated universe of large and `
      + 'mid caps — these are exactly the stocks everybody already follows. This section fills '
      + 'up once universe expansion starts promoting names from the wider market.');

  const coverage = el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `Scored ${scored.length} of ${rows.length} stocks. `
    + `${notMeasured} not measured — either below the quality bar (under-discovered junk is `
    + 'still junk) or missing ownership data. Those are blank, not zero.');

  return withCount(section('Under the Radar', explain,
    card(body, divergenceNote(picks), coverage)), picks.length);
}

/**
 * Where the score and the business disagree, said out loud.
 *
 * The composite and the compounding gate answer different questions — "what is working" against
 * "what can compound" — and 59% of the composite's weight is price behaviour, so they part company
 * often. Two badges side by side reading 78 and "No" look like the app arguing with itself unless
 * the difference is named, and SPEC 21's reader is not a stock-market expert.
 *
 * This is Gotcha 105's pattern, not Gotcha 85's: the vocabularies stay deliberately different and
 * the gap is explained rather than reconciled away. NOT_MEASURED is never counted as disagreement —
 * a business whose filings could not be read has not failed anything (Gotcha 44).
 */
function divergenceNote(picks) {
  const hollow = picks.filter((r) => r.compounding === 'NO');
  const quiet = picks.filter((r) => r.compounding === 'COMPOUNDER' && r.compositeScore < 70);
  if (!hollow.length && !quiet.length) return null;

  const parts = [];
  if (hollow.length) {
    parts.push(`${hollow.map((r) => displaySymbol(r.symbol)).join(', ')} `
      + `${hollow.length === 1 ? 'scores' : 'score'} well but does not clear the compounding gate — `
      + 'the score is largely price behaviour, so this is a stock that is working rather than a '
      + 'business proven to compound. Read the return-on-capital and debt columns before acting.');
  }
  if (quiet.length) {
    parts.push(`${quiet.map((r) => displaySymbol(r.symbol)).join(', ')} `
      + `${quiet.length === 1 ? 'clears' : 'clear'} the compounding gate on a modest score — `
      + 'good business, price not yet moving. That is the more interesting direction of the two.');
  }
  return el('div.row', {
    // Variables checked against css/app.css — there is no --accent (Gotcha 104).
    style: 'gap:10px;align-items:baseline;margin-top:12px;padding:10px 12px;'
      + 'border-left:3px solid var(--info);background:var(--info-bg);'
      + 'border-radius:var(--radius);font-size:13.5px',
  }, el('span', {}, parts.join(' ')));
}

// ---------------------------------------------------------------- buyability

function buyabilitySection(rows) {
  const tiers = { LIQUID: 0, MODERATE: 0, THIN: 0, UNKNOWN: 0 };
  for (const r of rows) {
    const t = r.liquidityTier && tiers[r.liquidityTier] !== undefined ? r.liquidityTier : 'UNKNOWN';
    tiers[t] += 1;
  }

  const explain = 'Whether you could actually build a position. A stock trading a few lakh a '
    + 'day may be a fine business and still take you months to accumulate — and would move the '
    + 'price against you on the way in and the way out. This never changes any score; it is an '
    + 'execution fact, not a quality judgement.';

  const cards = el('div.grid.kpis', {},
    kpi({ label: 'Easy to buy', value: num(tiers.LIQUID), sub: 'over ' + inr(50000000) + ' traded/day', tone: 'positive' }),
    kpi({ label: 'Workable', value: num(tiers.MODERATE), sub: inr(5000000) + '–' + inr(50000000) + '/day', tone: 'neutral' }),
    kpi({ label: 'Thin', value: num(tiers.THIN), sub: 'under ' + inr(5000000) + '/day', tone: tiers.THIN > 0 ? 'negative' : 'neutral' }),
    kpi({ label: 'Not measured', value: tiers.UNKNOWN ? num(tiers.UNKNOWN) : '0', sub: 'too little price history', tone: 'neutral' }));

  const thin = rows.filter((r) => r.liquidityTier === 'THIN' || (!missing(r.circuitDaysLast60) && r.circuitDaysLast60 >= 3));
  const warnings = thin.length
    ? table([
      { key: 'symbol', label: 'Stock', render: (r) => el('a', { href: stockHref(r.symbol) }, displaySymbol(r.symbol)) },
      { key: 'compositeScore', label: 'Score', align: 'r', render: (r) => num(r.compositeScore) },
      { key: 'liquidityAdv20d', label: 'Buyability', render: buyabilityCell },
      timingCol(),
    entryCol(),
    ], thin, { sortKey: 'compositeScore', filter: false })
    : null;

  return withCount(section('Can You Actually Buy It?', explain, card(cards,
    warnings
      ? el('div', {}, el('div.muted', { style: 'font-size:12.5px;margin:12px 0 6px' },
        'Flagged for thin trading or circuit locks — shown, never hidden:'), warnings)
      : el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
        'Nothing in this run is thin enough to be a problem to accumulate.'))),
  // The count is what the section FOUND — stocks flagged as hard to buy — not the universe it
  // looked at. A collapsed "Can You Actually Buy It? (284)" would read as 284 problems.
  thin.length);
}

// ------------------------------------------------------------ good and down

/**
 * Position in the 52-week range below which a stock counts as "down" for this lane.
 *
 * Measured, not assumed (Gotcha 76, the SPEC 12.11 precedent): on the screening run of
 * 2026-09-09, 284 stocks had a median range position of 65.2 and a 25th percentile of 34.6.
 * So 35 is the bottom quartile of the live cross-section, and it is re-derived rather than
 * inherited if the universe changes shape.
 */
const DOWN_BAR = 35;

/**
 * Quality strong enough that a fall is worth looking at rather than heeded.
 *
 * Deliberately NOT the composite. Gating on the composite is what makes every other lane on this
 * page momentum-positive: 59% of that weight is price behaviour, so a good business that has
 * fallen scores low *because* it has fallen. On the 2026-09-09 run the names this finds —
 * HINDUNILVR, DABUR, COLPAL, WIPRO, HAVELLS — score 21 to 52 and are invisible to a 65 floor.
 *
 * A HIGH forensic flag disqualifies outright: every quality figure here is computed *from* the
 * audited accounts, so a doubt about the accounts makes a clean reading meaningless rather than
 * reassuring (SPEC 41.2). MEDIUM cautions and INFO is not a stop (Gotcha 77).
 */
function qualityHolds(r) {
  if (worstFlag(r) === 'HIGH') return false;
  if (r.compounding === 'COMPOUNDER') return true;
  return r.compounding === 'PARTIAL'
    && String(r.financialQualityVerdict || '').toUpperCase() === 'HIGH_QUALITY';
}

/**
 * How far down, as a position and not half of one.
 *
 * Distance from the 52-week high alone cannot tell a stock that is still falling from one that
 * fell and has already bounced — a stock can be 13% off its high and still 53% above its low
 * (B-062). Both numbers are shown, and the range position is the one that sorts.
 */
function fallCell(r) {
  const posn = r.rangePosition52w;
  if (missing(posn)) return unmeasured('No 52-week range on the last screening for this stock');

  // Plain English first, and both numbers, because either one alone is half a position: "13% off
  // its high" and "53% above its low" describe the same stock and mean opposite things (B-062).
  // The distance from the high leads because it is the one a reader understands without being
  // told what it is; the range position is what the column sorts on.
  const off = missing(r.priceVs52WeekHigh)
    ? null
    : `${pct(r.priceVs52WeekHigh, { signed: false, digits: 0 })} off high`;
  const node = el('span', {
    title: `${off ? off + ', and ' : ''}${num(posn)}% of the way up its own 12-month range `
      + '(0 = sitting at the 12-month low, 100 = at the high). Both are shown because either one '
      + 'alone is half the picture: a stock can be well off its high and still far above its low, '
      + 'which is a stock that fell and has already bounced.',
  });
  node.append(el('span.cell-main.neg', {}, off || `${num(posn)}% of range`));
  node.append(el('span.cell-sub', {}, `${num(posn)}% above low`));
  return node;
}

function turnaroundCell(r) {
  const v = r.turnaroundVerdict;
  if (!v) {
    return unmeasured('Not checked — a turnaround verdict needs 3+ years of annual accounts on file');
  }
  return badge(v, {
    type: v === 'TURNAROUND_CANDIDATE' ? 'success' : 'neutral',
    label: v === 'TURNAROUND_CANDIDATE' ? 'Turning' : 'Not yet',
  });
}

/**
 * Good business, currently down — the lane every other section on this page cannot reach.
 *
 * Nothing here is scored and nothing enters the composite (Gotcha 30). It is a shortlist to
 * research: a quality business near the bottom of its own range is where a long holding period
 * is usually bought, and it is also exactly what a permanently falling business looks like on the
 * day before it keeps falling. The columns are the evidence for telling those apart.
 */
function contrarianSection(rows) {
  const explain = 'Businesses the app rates well that are trading near the bottom of their own '
    + '12-month range. The rest of this page can only find things that are already working — the '
    + 'main score is 59% price behaviour, so a good company that has fallen scores badly for '
    + 'having fallen. This lane deliberately ignores that score and gates on the business instead. '
    + 'It is a list to research, not a list to buy: this is also what a business in real trouble '
    + 'looks like, and the columns are here so you can tell the difference.';

  const picks = rows
    .filter((r) => !missing(r.rangePosition52w) && r.rangePosition52w <= DOWN_BAR && qualityHolds(r))
    .sort((a, b) => a.rangePosition52w - b.rangePosition52w);

  const cols = [
    symbolCol(),
    watchCol('From discovery: quality business near its 52-week low'),
    { key: 'rangePosition52w', label: 'How far down', align: 'r', value: (r) => r.rangePosition52w, render: fallCell },
    compoundingCol(),
    macroCol(),
    { key: 'financialQualityVerdict', label: 'Fin. quality', value: finQualityRank, render: finQualityCell },
    { key: 'turnaroundVerdict', label: 'Turning?', value: (r) => (r.turnaroundVerdict === 'TURNAROUND_CANDIDATE' ? 0 : r.turnaroundVerdict ? 1 : 2), render: turnaroundCell },
    ...businessCols(),
    { key: 'dcfVerdict', label: 'Valuation', value: valuationRank, render: valuationCell },
    { key: 'compositeScore', label: 'Score', align: 'r', render: scoreCell },
    { key: 'liquidityAdv20d', label: 'Buyability', render: buyabilityCell },
    timingCol(),
    entryCol(),
    sectorCol(),
  ];

  const body = picks.length
    ? table(cols, picks, { sortKey: 'rangePosition52w', sortDir: 'asc', filter: false })
    : empty('No quality business is near its lows in this run',
      'Every stock clearing the quality bar is trading in the upper two-thirds of its 12-month '
      + 'range. In a market that has risen broadly this is the expected result, and an empty list '
      + 'here is a finding rather than a failure.');

  return withCount(section('Good Business, Currently Down', explain,
    card(body, contrarianCoverage(rows, picks))), picks.length);
}

/**
 * What this lane could and could not judge.
 *
 * Mandatory, because two of its four business columns are silent for most of the universe and an
 * empty red-flag cell reads like a clean bill of health when it usually means nothing was checked
 * (Gotcha 44). Measured 2026-09-09: the forensic screen had run on 75 of 284 stocks and a
 * turnaround verdict existed for 141.
 */
function contrarianCoverage(rows, picks) {
  const total = rows.length;
  const screened = rows.filter((r) => worstFlag(r) !== null).length;
  const turnaround = rows.filter((r) => r.turnaroundVerdict).length;
  const quality = rows.filter((r) => r.compounding && r.compounding !== 'NOT_MEASURED').length;
  const growth = rows.filter((r) => !missing(r.yoyProfitGrowth) || r.earningsGrowthVerdict).length;

  const lines = [el('div', {},
    `${picks.length} of ${total} stocks are both rated well and near the bottom of their range `
    + '(bottom quarter, measured on this run). Coverage of the evidence behind that: the '
    + `compounding gate could be judged for ${quality}, the forensic red-flag screen has run on `
    + `${screened}, a turnaround verdict exists for ${turnaround}, and quarterly growth was read `
    + `for ${growth}. Where a check has not run the cell says so — an empty red-flag column means `
    + 'nobody looked, not that nothing is wrong.')];

  // Said plainly rather than left to be discovered. A quality business near its low is, by
  // definition, one whose price is weak, and the entry verdict is a momentum rule — so this lane
  // will normally be full of "Avoid" and "Hold off". That is the two questions disagreeing, not
  // the app contradicting itself: the business is worth researching AND the price has not stopped
  // falling. Both are true, and acting on the first while ignoring the second is how a cheap
  // stock becomes a cheaper one. The rule table is shared with every other surface (Gotcha 85) —
  // it is explained here, never re-written here.
  lines.push(el('div', { style: 'margin-top:6px' },
    'Expect the entry column to read "Avoid" or "Hold off" for most of this list. It answers a '
    + 'different question — has the fall stopped? — and for a stock near its low the answer is '
    + 'usually not yet. Treat this section as a research queue and the entry column as the timing '
    + 'on top of it; a business worth owning and a price that has finished falling rarely arrive '
    + 'on the same day.'));

  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' }, ...lines);
}

// -------------------------------------------------------------- insider pulse

/** Modes that represent a real conviction trade, mirroring the backend's filter. */
const SIGNAL_MODES = new Set(['MARKET_PURCHASE', 'MARKET_SALE']);
const SIGNAL_CATEGORIES = new Set(['PROMOTER', 'PROMOTER_GROUP', 'DIRECTOR', 'KMP', 'RELATIVE']);

function isSignalRow(d) {
  return SIGNAL_MODES.has(d.mode) && SIGNAL_CATEGORIES.has(d.personCategory);
}

function insiderSection(disclosures) {
  const explain = 'Company insiders — founders, directors, senior management — must disclose '
    + 'trades in their own shares. Buying with their own money at the market price is one of the '
    + 'few genuinely early signals available free. Most of the raw feed is not that: share grants, '
    + 'loan pledges, gifts and transfers between family members all appear here and none of them '
    + 'is a conviction bet, so they are recorded but never scored.';

  if (!disclosures || disclosures.length === 0) {
    return section('Insider Activity', explain,
      empty('No disclosures captured yet',
        'Insider filings are collected each weekday at 2:45pm. This fills in as companies file.'));
  }

  const signal = disclosures.filter(isSignalRow);
  // Exchange bulk and block deals, stored by InsiderDisclosureService with personCategory
  // "INSTITUTION" and mode "BULK_DEAL". They are not insider trades and are not scored — but they
  // are not grants, pledges or gifts either, and until 2026-09-09 the footer said they were. A
  // named fund taking a block is the single most-followed discovery route in this market; it is
  // counted honestly here and left unjudged rather than mislabelled.
  const deals = disclosures.filter((d) => d.mode === 'BULK_DEAL'
    || d.source === 'BULK' || d.source === 'BLOCK');
  const noise = disclosures.length - signal.length - deals.length;

  const cols = [
    { key: 'transactionDate', label: 'Date', render: (r) => shortDate(r.transactionDate), value: (r) => r.transactionDate },
    { key: 'symbol', label: 'Stock', render: (r) => el('a', { href: stockHref(r.symbol) }, displaySymbol(r.symbol)) },
    watchCol('From discovery: insider activity'),
    { key: 'personName', label: 'Who', render: (r) => el('span', { title: r.personName || '' }, r.personName || '—') },
    { key: 'personCategory', label: 'Role', render: (r) => badge(r.personCategory, { type: 'info' }) },
    {
      key: 'transactionType',
      label: 'Action',
      render: (r) => badge(r.transactionType, {
        type: r.transactionType === 'BUY' ? 'success' : 'danger',
        label: r.transactionType === 'BUY' ? 'Bought' : 'Sold',
      }),
    },
    { key: 'value', label: 'Value', align: 'r', render: (r) => (missing(r.value) ? unmeasured('The filing did not state a value') : inr(r.value)) },
    timingCol(),
    entryCol(),
  ];

  const shownSignal = INSIDER_FILTERS.apply(signal);
  const body = signal.length
    ? el('div', {}, INSIDER_FILTERS.bar(signal, shownSignal.length, 'filings'),
      table(cols, shownSignal.slice(0, 60), { sortKey: 'transactionDate', filter: false }))
    : empty('No open-market insider trades in this window',
      `${disclosures.length} filings were captured, but none was an insider buying or selling in `
      + `the open market — ${deals.length} were bulk or block trades by outside funds and the rest `
      + 'were share grants, pledges, gifts or transfers between promoters. None is a conviction '
      + 'purchase by an insider, so none is scored.');

  const parts = [`${signal.length} open-market insider trades out of ${disclosures.length} filings captured.`];
  if (deals.length) {
    parts.push(`${deals.length} were large bulk or block trades on the exchange by funds and other `
      + 'outside buyers. Those are not insider trades, so this table leaves them out and nothing '
      + 'scores them — but they are captured and kept, not discarded.');
  }
  parts.push(`The remaining ${noise} were share grants, pledges, gifts or promoter-to-promoter `
    + 'transfers — recorded for completeness, deliberately excluded from any score.');
  const note = el('div.muted', { style: 'font-size:12.5px;margin-top:10px' }, parts.join(' '));

  return withCount(section('Insider Activity', explain,
      card(body, note, timingCoverage(signal))), signal.length);
}

// ------------------------------------------------------------ dynamic universe

function universeSection(u) {
  const explain = 'The screener normally looks at about 361 hand-picked stocks. Multibaggers '
    + 'usually emerge from the wider market of roughly 2,300 listed companies, while nobody is '
    + 'watching. This is the funnel that lets the system find names for itself: a cheap weekly '
    + 'pass over the whole market on price and volume, then a full workup on the survivors a few '
    + 'at a time.';

  if (!u) {
    return section('Universe Expansion', explain,
      empty('Not available', 'The expansion endpoint could not be reached.'));
  }

  // Each list carries its own status so one chip bar can narrow all three; the API returns them
  // pre-split, so the status is stamped here rather than guessed from which array it arrived in.
  const stamp = (arr, status) => (arr || []).map((r) => (r.status ? r : { ...r, status }));
  const allFunnelRows = [
    ...stamp(u.promoted, 'PROMOTED'),
    ...stamp(u.queued, 'QUEUED'),
    ...stamp(u.retired, 'RETIRED'),
  ];
  const kept = new Set(UNIVERSE_FILTERS.apply(allFunnelRows).map((r) => r.symbol + ':' + r.status));
  const keep = (arr, status) => stamp(arr, status).filter((r) => kept.has(r.symbol + ':' + r.status));

  const promoted = keep(u.promoted, 'PROMOTED');
  const queued = keep(u.queued, 'QUEUED');
  const retired = keep(u.retired, 'RETIRED');
  const recent = u.promotedLast7Days || [];

  const banner = u.enabled
    ? null
    : alert({
      severity: 'INFO',
      title: 'Observation mode',
      message: 'Stocks are being discovered, queued and scored, but they do not yet enter the '
        + 'screening universe. The promotion rules have no track record, and a wider universe is '
        + 'full of thin, lightly-covered names — so the funnel is being watched before it is '
        + 'allowed to change what gets screened.',
    });

  const cards = el('div.grid.kpis', {},
    kpi({ label: 'In the universe', value: num(promoted.length), sub: `cap ${u.maxActiveSymbols}`, tone: 'positive' }),
    kpi({ label: 'Awaiting a full look', value: num(queued.length), sub: 'deep-scored a few per day', tone: 'neutral' }),
    kpi({ label: 'Added this week', value: num(recent.length), sub: 'newly promoted', tone: recent.length ? 'positive' : 'neutral' }),
    kpi({ label: 'Dropped', value: num(retired.length), sub: 'kept on record, not deleted', tone: 'neutral' }));

  const cols = [
    { key: 'symbol', label: 'Stock', render: (r) => el('a', { href: stockHref(r.symbol) }, displaySymbol(r.symbol)) },
    { key: 'companyName', label: 'Company', render: (r) => el('span.faint', {}, r.companyName || '—') },
    watchCol('From discovery: universe expansion funnel'),
    { key: 'sourceReason', label: 'Found by', render: (r) => badge(r.sourceReason, { type: 'info' }) },
    { key: 'coarseScore', label: 'First pass', align: 'r', render: (r) => scoreBar(r.coarseScore, { width: 44 }) },
    { key: 'lastCompositeScore', label: 'Full score', align: 'r', render: (r) => scoreBar(r.lastCompositeScore, { width: 44 }) },
    timingCol(),
    entryCol(),
    { key: 'discoveredDate', label: 'Found', render: (r) => shortDate(r.discoveredDate), value: (r) => r.discoveredDate },
    { key: 'note', label: 'Status', render: (r) => el('span.faint', { style: 'font-size:12px' }, r.note || '—') },
  ];

  const lists = [];
  if (promoted.length) {
    lists.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'In the screening universe'), table(cols, promoted, { sortKey: 'lastCompositeScore', filter: false }));
  }
  if (queued.length) {
    lists.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'Queued for a full workup'), table(cols, queued, { sortKey: 'coarseScore', filter: false }));
  }
  if (retired.length) {
    // Shown deliberately: a funnel that only displays its winners cannot be judged.
    lists.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'Dropped, kept for the record'), table(cols, retired.slice(0, 30), { sortKey: 'discoveredDate', filter: false }));
  }

  const body = lists.length
    ? el('div', {}, ...lists)
    : empty('The funnel has not run yet',
      'The weekly market-wide scan runs on Saturday morning. Until then there is nothing to show.');

  const shownFunnel = promoted.length + queued.length + retired.length;
  return withCount(section('Universe Expansion', explain, banner,
      card(cards, UNIVERSE_FILTERS.bar(allFunnelRows, shownFunnel, 'symbols'),
        body, timingCoverage([...promoted, ...queued, ...retired]))), allFunnelRows.length);
}

// ----------------------------------------------------------------- IPO watch

/** Stage -> how it reads and how it looks. The vocabulary is SPEC 45.4's, not this page's. */
const IPO_STAGE = {
  BASE_FORMING: { type: 'success', label: 'Base forming' },
  RECOVERING: { type: 'info', label: 'Recovering' },
  WASHOUT: { type: 'warning', label: 'Washout' },
  HYPE_WINDOW: { type: 'neutral', label: 'Too early' },
};

const stageRank = (r) => {
  const order = { BASE_FORMING: 0, RECOVERING: 1, WASHOUT: 2, HYPE_WINDOW: 3 };
  return r.stage in order ? order[r.stage] : 4;
};

/**
 * Recent listings, deferring to the IPO tracker (SPEC 45).
 *
 * This section used to run its own engine: a six-minute Kite sweep behind a button answering with
 * a boolean "setup present". SPEC 45 then shipped a DB-only tracker answering the same question
 * with a four-value cycle stage and the lock-in calendar behind it. Two rule tables for one
 * question in one vocabulary is Gotcha 85's failure, so the newer surface wins and this one reads
 * it — which also makes the section free on page load instead of costing six minutes.
 *
 * The full issue detail, lock-in dates and application sizing live on the IPO screen; this is the
 * discovery-shaped slice of it.
 */
function ipoSection(data) {
  const explain = 'Newly listed companies produce a lot of multibaggers, but almost never during '
    + 'the excitement around the listing itself. The sellers arrive on a timetable — anchor '
    + 'investors at 30 and 90 days, pre-IPO holders at six months — so the setup worth watching is '
    + 'the opposite of the listing pop: the hype gone, the early sellers finished, the price '
    + 'building a base above where it listed.';

  if (!data) {
    return section('Recent Listings', explain,
      empty('Not available', 'The IPO tracker could not be reached.'));
  }

  const rows = data.listings || [];
  if (rows.length === 0) {
    return section('Recent Listings', explain,
      empty('No recent mainboard listings tracked',
        'The pipeline is captured each weekday at 12:15. This fills in as issues list.'));
  }

  const ready = rows.filter((r) => r.stage === 'BASE_FORMING' || r.stage === 'RECOVERING');
  const early = rows.filter((r) => r.stage === 'HYPE_WINDOW').length;
  const unmeasuredCount = rows.filter((r) => !IPO_STAGE[r.stage]).length;

  const cols = [
    symbolCol(),
    { key: 'companyName', label: 'Company', render: (r) => el('span.faint', {}, r.companyName || '—') },
    watchCol('From discovery: recent listing'),
    { key: 'listingDate', label: 'Listed', render: (r) => shortDate(r.listingDate), value: (r) => r.listingDate },
    { key: 'monthsSinceListing', label: 'Age', align: 'r', render: (r) => (missing(r.monthsSinceListing) ? unmeasured('Listing date not confirmed by the exchange feed') : `${r.monthsSinceListing} mo`) },
    {
      key: 'stage',
      label: 'Where in the cycle',
      value: stageRank,
      render: (r) => {
        const spec = IPO_STAGE[r.stage];
        if (!spec) return unmeasured(r.stageReason || 'Not enough price history since listing to judge');
        const node = badge(r.stage, spec);
        node.title = r.stageReason || '';
        return node;
      },
    },
    { key: 'vsIssuePricePct', label: 'vs issue price', align: 'r', render: (r) => (missing(r.vsIssuePricePct) ? unmeasured('No issue price or no recent price on file') : el('span.' + (r.vsIssuePricePct >= 0 ? 'pos' : 'neg'), {}, pct(r.vsIssuePricePct))) },
    { key: 'vsListingHighPct', label: 'vs listing-day high', align: 'r', render: (r) => (missing(r.vsListingHighPct) ? unmeasured('Listing-day high not captured for this issue') : el('span.' + (r.vsListingHighPct >= 0 ? 'pos' : 'neg'), {}, pct(r.vsListingHighPct))) },
    { key: 'financialQualityVerdict', label: 'Fin. quality', value: finQualityRank, render: finQualityCell },
    // The two shared timing columns are deliberately NOT here, and this is the one table on the
    // page they are wrong for. "Is it still a good time to buy?" is answered off the screening
    // row, and a recent listing is almost never in the screening universe — measured 2026-09-10,
    // 3 of 238. A column that reads "not measured" on 235 rows out of 238 is not reporting a
    // gap, it is spending the column budget (SPEC 27.10) to say nothing 99% of the time, and it
    // makes a table that is working look broken. What CAN fill this row is the Analyse button.
    analyseCol(),
  ];

  const analysed = rows.filter((r) => r.lastAnalysedAt).length;
  const note = el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `${rows.length} mainboard listings in the last ${data.windowMonths} months. `
    + `${ready.length} are past the six-month mark and building or recovering; ${early} are still `
    + `inside the hype window, where the stage is reported as "too early" however good the chart `
    + `looks; ${unmeasuredCount} could not be judged for want of price history. `
    + 'A shortlist to research, not a list of buys — the pattern is common in a rising market and '
    + 'has no measured track record here. The full issue detail, lock-in dates and application '
    + 'sizing are on the IPO screen. '
    // Coverage, stated rather than left for the reader to infer from a column of stripes
    // (Gotcha 44). A newly listed company has no filings history on file, so the business
    // columns are blank until somebody asks for that listing by name.
    + `Business quality has been fetched for ${analysed} of ${rows.length}: a company this new has `
    + 'no filings on file, so the Analyse button on a row goes and reads its first results and '
    + 'shareholding from NSE. It takes a few seconds and the answer is kept.');

  const link = el('div', { style: 'margin-top:8px;display:flex;gap:18px;flex-wrap:wrap' },
    // The stage words are the app's own vocabulary, not market usage, and a filter chip is the
    // worst place to meet a word for the first time. Both tables that use them link here.
    el('a', { href: 'guide.html#ipo-stages' }, 'What do “base forming”, “recovering” and “washed out” mean? →'),
    el('a', { href: 'ipo.html' }, 'Open the IPO screen →'));

  const shownIpo = IPO_FILTERS.apply(rows);
  // withCount takes rows.length, NOT ready.length. The heading said 54 while the table held 238,
  // which was survivable while you could scroll past it and is not once the heading is the
  // navigation: a count beside a list must describe that list (Gotcha 98). The 54 is still
  // reported — it is the "past the six-month mark" figure in the note below.
  return withCount(section('Recent Listings', explain,
    card(IPO_FILTERS.bar(rows, shownIpo.length, 'listings'),
      table(cols, shownIpo, { sortKey: 'stage', sortDir: 'asc', filter: false }),
      note, link)), rows.length);
}
// ---------------------------------------------------------------------- boot

/**
 * Chips for the three tables that are NOT screening rows (SPEC 27.13).
 *
 * These three are 84% of this page's height, and until now they had no chips at all — the
 * universe bar above cannot reach them, because an insider filing and a queued symbol are not
 * screening rows and share none of its fields. The three stock lanes deliberately keep sharing
 * the one universe bar instead of growing three near-identical ones: they are three questions
 * asked of a single screening run, so a chip there is a statement about the universe, and three
 * copies of it would be three things to keep in step (Gotcha 85's shape, one level up).
 */
const INSIDER_FILTERS = chipFilters([
  {
    label: 'Action:',
    key: 'side',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'BUY', text: 'Bought', test: (r) => r.transactionType === 'BUY' },
      { value: 'SELL', text: 'Sold', test: (r) => r.transactionType === 'SELL' },
    ],
  },
  {
    label: 'Who:',
    key: 'who',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'PROMOTER', text: 'Promoter', test: (r) => /PROMOTER/i.test(r.personCategory || '') },
      { value: 'DIRECTOR', text: 'Director', test: (r) => /DIRECTOR/i.test(r.personCategory || '') },
      { value: 'KMP', text: 'Key management', test: (r) => /KMP|KEY/i.test(r.personCategory || '') },
    ],
  },
  {
    label: 'Size:',
    key: 'size',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'BIG', text: 'Over ₹1 crore', test: (r) => !missing(r.value) && r.value >= 10000000,
      },
      {
        value: 'NO_VALUE', text: 'No value stated',
        test: (r) => missing(r.value),
        title: 'The filing did not state a rupee amount. Not a small trade — an unstated one (Gotcha 21).',
      },
    ],
  },
  { search: true, placeholder: 'Search a stock or a name…' },
], (opts) => paint(opts));

const UNIVERSE_FILTERS = chipFilters([
  {
    label: 'Status:',
    key: 'status',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'PROMOTED', text: 'In the universe', test: (r) => r.status === 'PROMOTED' },
      { value: 'QUEUED', text: 'Queued', test: (r) => r.status === 'QUEUED' },
      {
        value: 'RETIRED', text: 'Dropped', test: (r) => r.status === 'RETIRED',
        title: 'Retired after eight consecutive weak weeks. Kept on the record — a funnel that shows only its winners cannot be judged (SPEC 25.1).',
      },
    ],
  },
  { label: 'Found by:', key: 'sourceReason', ownLine: true, options: (rs) => sectorOptions(rs, { key: 'sourceReason', label: humanLabel }) },
  { search: true, placeholder: 'Search a symbol…' },
], (opts) => paint(opts));

const IPO_FILTERS = chipFilters([
  {
    label: 'Where in the cycle:',
    key: 'stage',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'BASE_FORMING', text: 'Base forming', test: (r) => r.stage === 'BASE_FORMING',
        title: 'Past the lock-in cliff and no longer falling — the bucket worth reading a prospectus for.',
      },
      { value: 'RECOVERING', text: 'Recovering', test: (r) => r.stage === 'RECOVERING' },
      { value: 'WASHOUT', text: 'Washed out', test: (r) => r.stage === 'WASHOUT' },
      {
        value: 'HYPE_WINDOW', text: 'Still too early', test: (r) => r.stage === 'HYPE_WINDOW',
        title: 'Listed under six months ago. Shown, never judged — the sellers have not arrived yet (SPEC 45.4).',
      },
      {
        value: 'NOT_MEASURED', text: 'Not measured',
        test: (r) => !r.stage || r.stage === 'NOT_MEASURED',
        title: 'No usable price history since listing. A gap in the app, never folded into "washed out".',
      },
    ],
  },
  {
    label: 'Against issue price:',
    key: 'vsIssue',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'UP', text: 'Above it', test: (r) => !missing(r.vsIssuePricePct) && r.vsIssuePricePct > 0 },
      { value: 'DOWN', text: 'Below it', test: (r) => !missing(r.vsIssuePricePct) && r.vsIssuePricePct < 0 },
    ],
  },
  { search: true, placeholder: 'Search a listing…' },
], (opts) => paint(opts));

let loaded = { rows: [], screeningDate: null, insider: [], universe: null, ipo: null };

/**
 * Discovery chips (SPEC 27.13). Fewer groups than the screener on purpose: this page's lanes
 * already apply their own gates, so the chips here answer "which corner of the universe am I
 * looking at", not "which stocks are good" — that second question is what the lanes are.
 */
const FILTERS = chipFilters([
  {
    label: 'Compounding:',
    key: 'compounding',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'COMPOUNDER', text: 'Compounders only', test: (r) => r.compounding === 'COMPOUNDER' },
      {
        value: 'EITHER', text: 'Compounder or partial',
        test: (r) => r.compounding === 'COMPOUNDER' || r.compounding === 'PARTIAL',
      },
    ],
  },
  {
    label: 'Owned:',
    key: 'held',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'NOT_HELD', text: 'I do not own it', test: (r) => !r.inHoldings },
      { value: 'HELD', text: 'I own it', test: (r) => !!r.inHoldings },
    ],
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
    label: 'Risk:',
    key: 'risk',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'CLEAN', text: 'Hide red flags',
        test: (r) => { const w = worstFlag(r); return w !== 'HIGH' && w !== 'MEDIUM'; },
        title: 'Hides high and medium red flags. Stocks nobody examined stay visible — unchecked is not clean.',
      },
    ],
  },
  { label: 'Sector:', key: 'sector', ownLine: true, options: (rs) => sectorOptions(rs, { label: humanLabel }) },
  { search: true, placeholder: 'Search a stock or sector…' },
], (opts) => paint(opts));

async function render() {
  view.replaceChildren(el('section.section', {},
    el('h2.section-title', {}, 'Loading discovery…'), skeleton(4)));
  await initChrome();

  let screener = { data: null };
  let universe = { data: null };
  let insider = { data: [] };
  let ipo = { data: null };

  try {
    [screener, universe, insider, ipo, watched] = await Promise.all([
      get('/api/dashboard/screener', { fallback: null }).catch(() => ({ data: null })),
      get('/api/universe/dynamic', { fallback: null }).catch(() => ({ data: null })),
      get('/api/insider/recent?days=45', { fallback: [] }).catch(() => ({ data: [] })),
      // DB-only (SPEC 45), so it belongs on the page load. The section it replaced ran a
      // six-minute Kite sweep behind a button.
      get('/api/ipo/recent?months=36', { fallback: null }).catch(() => ({ data: null })),
      loadWatchedSet(), // DB-only (SPEC 37), verified by hand
    ]);
  } catch (err) {
    mount(view, empty('Could not load discovery data', String(err.message || err)));
    return;
  }

  loaded = {
    rows: (screener.data && screener.data.scores) || [],
    screeningDate: screener.data ? screener.data.screeningDate : null,
    insider: insider.data,
    universe: universe.data,
    ipo: ipo.data,
  };
  timing = new Map(loaded.rows.map((r) => [r.symbol, r]));
  paint();
}

/**
 * Draw, from what render() already fetched.
 *
 * The chips narrow the SCREENING ROWS before the three stock lanes see them, rather than each
 * lane growing a bar of its own. That is the honest shape: the lanes are three questions asked
 * of one universe, so "only compounders" or "only pharma" is a statement about the universe and
 * every lane should answer within it. The insider, universe-expansion and listings tables are
 * different row shapes entirely and are deliberately left alone — they keep the per-table search.
 */
function paint(opts = {}) {
  const { rows, screeningDate } = loaded;
  const shown = FILTERS.apply(rows);

  const header = rows.length
    ? el('div.muted', { style: 'font-size:12.5px;margin-bottom:12px' },
      `Based on the screening run of ${shortDate(screeningDate)} — ${rows.length} stocks.`)
    : alert({
      severity: 'WARNING',
      title: 'No screening data',
      message: 'The screener has not stored a run yet, so the under-the-radar and buyability '
        + 'sections have nothing to work from.',
    });

  const bar = rows.length
    ? el('div', { style: 'margin-bottom:14px' },
      el('div.muted', { style: 'font-size:12.5px;margin-bottom:6px' },
        'Narrow the universe these lanes search:'),
      FILTERS.bar(rows, shown.length, 'stocks'))
    : null;

  // Every section folds, and the reader's choice sticks per section in this browser (SPEC 27.15).
  //
  // Everything starts folded, on this page as on every other. The earlier rule here opened the
  // first lane that had actually found something — which existed to avoid opening an empty
  // section while folding the ones with findings. Opening nothing avoids that too, and the
  // count each heading carries is what still separates "I chose not to look at this" from
  // "I did not know there was anything to look at".
  //
  // The explicit keys stay: they are what a reader's stored choices are filed under, and the
  // page-wide pass in mount() deliberately leaves an already-folding section alone.
  const sections = [
    ['under-radar', underRadarSection(shown)],
    ['currently-down', contrarianSection(shown)],
    ['buyable', buyabilitySection(shown)],
    ['insider', insiderSection(loaded.insider)],
    ['universe', universeSection(loaded.universe)],
    ['listings', ipoSection(loaded.ipo)],
  ].filter(([, node]) => node);

  mount(view,
    header,
    bar,
    sections.map(([key, node]) => collapse(node, {
      key: 'discovery.' + key,
      open: false,
    })));

  if (opts.keepFocus) {
    const box = view.querySelector('input[type="search"]');
    if (box) {
      box.focus();
      box.setSelectionRange(box.value.length, box.value.length);
    }
  }
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
render().then(() => registerRefresh(render)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '',
    empty('Could not load discovery', String(err && err.message ? err.message : err))));
});
