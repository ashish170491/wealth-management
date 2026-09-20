/**
 * Funds - the mutual funds this portfolio holds, measured honestly (SPEC 52).
 *
 * Why this screen looks nothing like a fund website.
 *
 * Every fund site in the country answers "which funds are performing well?" by sorting on
 * trailing return. That is the most thoroughly debunked selection signal in retail investing,
 * and it is the same failure this codebase has already deleted three engines for: sector
 * reversal, the breakout scanner and the ML chain all ranked things by a number that had never
 * been shown to predict anything (SPEC 39.3, Gotcha 25). So there is no score on this page, no
 * ranking, no "top funds" list and no sort that could be mistaken for one. The tables default to
 * the order the funds arrived in.
 *
 * What replaces it, in order of how much the evidence supports it:
 *
 *   1. WHAT THE PLAN COSTS. The single most reliable finding here, and the only one that does not
 *      decay. The Direct and Regular plans of one scheme are two published NAV series over one
 *      identical portfolio, so the gap between their compound rates IS the distributor
 *      commission - measured, per fund, per year, from a free file. Past return does not persist;
 *      that charge is levied every year regardless.
 *   2. THE RECORD OVER ROLLING WINDOWS, COUNTED. Not an average - an average lets one spectacular
 *      window carry a decade, which is how a factsheet is built. The count is beside its own
 *      denominator, and beside `independentWindows`, which is the only figure here that behaves
 *      like a sample size. ~100 months of history gives ~60 three-year windows and TWO
 *      independent ones, so no significance language appears anywhere on this screen.
 *   3. WHERE IT SAT IN ITS OWN CATEGORY, with the peer count printed, and refused outright below
 *      ten peers (Contra has four schemes in the whole market).
 *   4. HOW MUCH OF THAT CATEGORY DID NOT SURVIVE. The peer median is a median of survivors, and
 *      the attrition figure says by how much it is flattered. A fund's OWN record is not
 *      survivorship-biased; only the comparison is, and conflating those would let a real finding
 *      be waved away.
 *
 * And the refusals are the feature. An income-distribution series is not compared with Growth
 * peers - its NAV falls by every payout, so the two are different quantities and comparing them
 * understates the fund the investor owns (B-131). A category with too few peers gets no quartile
 * ever. A fund with under three years on file reads "not measured", which is this app's gap and
 * not a poor record. Those four states are counted separately in the coverage line, because a
 * summary that distinguishes fewer states than the rows beneath it is how three of six holdings
 * got reported as measured when one carried a verdict (B-117).
 *
 * Page-load requests, all four verified DB-only by hand - a page-load get() is ungated and only
 * getOnDemand() has the allowlist (Gotcha 39):
 *   GET /api/mf/portfolio   the measured record per held fund, plus the coverage line
 *   GET /api/mf/holdings    units, cost, NAV and its date
 *   GET /api/mf/history     how deep the month-end archive goes
 *   GET /api/mf/coverage    what the app can see of the market, and what it could not classify
 */

import { get, OfflineError } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import {
  el, section, collapse, withCount, withSummary, table, kpi, empty, skeleton, mount, badge,
  unmeasured, alert,
} from './ui.js';
import { inr, pct, num, shortDate, missing, sign, humanLabel } from './format.js';
import {
  schemeCell, planCell, recordCell, standingCell, planCostCell, verdictBadge,
} from './fund-cells.js';

const view = document.getElementById('view');

let lens = null;      // /api/mf/portfolio
let holdings = null;  // /api/mf/holdings
let history = null;   // /api/mf/history
let coverage = null;  // /api/mf/coverage

// ---------------------------------------------------------------------- the money

/**
 * Four tiles. The gain tile is the one that needs care.
 *
 * A position transferred in from another platform arrives with a broker cost basis of 0.0, which
 * is "cost unknown" and not "free" - so it is excluded from the invested and gain totals and
 * counted separately rather than being allowed to report an infinite return.
 */
function moneyCards() {
  if (!holdings) return null;
  const withoutCost = holdings.holdingsWithoutCostBasis || 0;
  const gainPct = holdings.totalInvested > 0
    ? ((holdings.totalCurrent - holdings.totalInvested) / holdings.totalInvested) * 100 : null;
  return el('div.grid.kpis', {},
    kpi({
      label: 'Funds held',
      value: num(holdings.count, 0),
      sub: holdings.navDateInFile ? 'NAV as of ' + shortDate(holdings.navDateInFile) : 'no NAV date on file',
    }),
    kpi({
      label: 'Value today',
      value: inr(holdings.totalCurrent),
      sub: 'at the last published NAV, not a live price',
    }),
    kpi({
      label: 'You paid',
      value: missing(holdings.totalInvested) || holdings.totalInvested <= 0
        ? 'not measured' : inr(holdings.totalInvested),
      sub: withoutCost
        ? withoutCost + ' position' + (withoutCost === 1 ? '' : 's') + ' with no cost on file, left out'
        : 'from the cost basis the broker reports',
      tone: 'neutral',
    }),
    kpi({
      label: 'Gain',
      value: missing(gainPct) ? 'not measured' : inr(holdings.totalPnl),
      raw: holdings.totalPnl,
      tone: 'auto',
      sub: missing(gainPct)
        ? 'no cost basis on file, so this cannot be computed'
        : pct(gainPct) + ' on what you paid - not a return, because it ignores when you paid it',
    }));
}

/**
 * The standing "what this means" box (SPEC 21 - the reader is not a market professional).
 *
 * The stance goes at the top and on screen, not in a tooltip: the reader most likely to act on a
 * fund page is the one least likely to hunt for its caveat.
 */
function stanceBox() {
  return el('div.info-box', {},
    el('h4', {}, 'What this page does, and what it deliberately will not do'),
    el('p', {},
      el('strong', {}, 'There is no score and no ranking here, on purpose. '),
      'Fund websites rank funds by how much they returned over the last three years. That is the '
      + 'single most debunked way of choosing a fund there is - the funds at the top of a '
      + 'three-year table are not the funds at the top of the next one, and sorting by it mostly '
      + 'measures which corner of the market happened to run. So this page counts and describes; '
      + 'it never orders funds best to worst.'),
    el('p', {},
      el('strong', {}, 'The cost reading is the one that lasts. '),
      'Every scheme comes in two plans over one identical portfolio: Direct, and Regular, which '
      + 'pays a commission to whoever sold it to you. The difference in what they compound at is '
      + 'that commission, and it is charged every year whether the fund does well or badly. Past '
      + 'return does not persist. That charge does.'),
    el('p', {},
      el('strong', {}, '"Not measured" is this app saying it cannot see something. '),
      'It is never a judgement on the fund. A fund needs three years of month-end prices on file '
      + 'before any rolling figure means anything, and a newly launched fund simply has not got '
      + 'them yet. "Not comparable" is a different thing again: the fund was measured perfectly '
      + 'well and there is nothing like-for-like to set it against.'),
    lens && lens.caveat ? el('p.faint', {}, lens.caveat) : null);
}

const HOLDING_COLS = [
  { key: 'fundName', label: 'Fund', render: (h) => schemeCell(h) },
  { key: 'plan', label: 'Plan', render: (h) => planCell(h) },
  { key: 'units', label: 'Units', align: 'r', render: (h) => num(h.units, 3) },
  {
    key: 'nav',
    label: 'NAV',
    align: 'r',
    // The date is part of the figure, never a separate column the eye can skip. A fund has one
    // price a day, struck after the market closes, so the newest NAV is always at least a day
    // old - and a NAV printed without its date is a figure on a scale it was not measured on
    // (SPEC 52.6, Gotcha 140).
    render: (h) => (missing(h.nav) ? unmeasured('No NAV on file for this scheme.')
      : el('div', {},
        el('div', {}, num(h.nav, 4)),
        el('div.faint', { style: 'font-size:12px' },
          h.navAsOf ? 'as of ' + shortDate(h.navAsOf) : 'no date on file'))),
  },
  {
    key: 'investedValue',
    label: 'You paid',
    align: 'r',
    render: (h) => (missing(h.investedValue) || h.investedValue <= 0
      ? unmeasured('The broker reports no cost for this position - units transferred in carry no '
        + 'purchase price. Cost unknown, not free.')
      : inr(h.investedValue)),
  },
  { key: 'currentValue', label: 'Worth now', align: 'r', render: (h) => inr(h.currentValue) },
  {
    key: 'pnl',
    label: 'Gain',
    align: 'r',
    render: (h) => (missing(h.investedValue) || h.investedValue <= 0
      ? unmeasured('No cost on file, so a gain cannot be computed.')
      : el('span', { class: sign(h.pnl) }, inr(h.pnl))),
  },
];

// ---------------------------------------------------------------------- one fund's record

/**
 * One labelled reading with its own explanation underneath.
 *
 * `refusal` is drawn at full weight rather than faint, and is never left in a tooltip. When the
 * answer is "we will not tell you that", the reason IS the content: a badge reading "Nothing to
 * compare against" with the explanation on hover is a refusal the reader cannot evaluate, and the
 * reader most likely to act on a fund page is the one least likely to go looking (SPEC 51's rule
 * about rendering a caution on screen, not in a tooltip).
 */
function reading(title, node, note, refusal) {
  return el('div', { style: 'margin:14px 0' },
    el('div', { style: 'font-weight:600;margin-bottom:4px' }, title),
    node,
    refusal ? el('p.muted', { style: 'font-size:13px;margin:6px 0 0' }, refusal) : null,
    note ? el('p.faint', { style: 'font-size:12.5px;margin:6px 0 0' }, note) : null);
}

/** The sentence behind a verdict, but only when the verdict is a refusal. */
function refusalReason(verdict, reason) {
  return (verdict === 'NOT_COMPARABLE' || verdict === 'NOT_MEASURED') && reason ? reason : null;
}

/**
 * Everything the app can say about one fund.
 *
 * Note what leads: the cost, then the record, then the standing. That order is the strength of
 * the evidence, not the order a fund site would use.
 */
function fundSection(h) {
  const measured = h.windowsMeasured > 0;
  // A quartile was actually published. The survivorship figure exists to qualify THAT comparison
  // and nothing else, so showing it where no comparison was made invites it to be read as a fact
  // about the fund - "0% of the category gone since 2025-08" over three index funds is arithmetic,
  // not a finding (the SPEC 52.9 rule: a fund's own record is not survivorship-biased, only the
  // peer comparison is, and conflating them is what lets a real finding be waved away).
  const ranked = h.categoryStanding
    && h.categoryStanding !== 'NOT_MEASURED'
    && h.categoryStanding !== 'TOO_FEW_PEERS';

  const body = el('div', {},
    reading('What the plan costs you', planCostCell(h), h.planCostCaveat),
    // The server's own reason line names what the record was measured against (the category
    // median), so the heading stays generic rather than asserting a benchmark the payload does
    // not separately carry.
    reading('Its record against its own category', recordCell(h),
      measured ? h.sampleSizeNote : null,
      refusalReason(h.recordVerdict, h.recordReason)),
    // The survivorship caveat belongs to exactly one of these two blocks, not both: it appeared
    // under the standing AND again under the attrition figure, which is the same sentence twice
    // on one panel.
    reading('Where it sat inside ' + (h.categoryLabel || humanLabel(h.category)),
      standingCell(h), ranked ? h.survivorshipCaveat : null,
      // Suppressed when it would simply repeat the record's refusal a second time on one panel:
      // an IDCW series fails both readings for the identical reason, and printing the same
      // paragraph twice is what teaches a reader to skip both.
      h.categoryStandingReason === h.recordReason
        ? null : refusalReason(h.categoryStanding, h.categoryStandingReason)),

    // Two readings that answer different questions can both be right and contradict each other -
    // a fund that trailed for years and has been strong lately. The gap IS the finding, so it is
    // explained rather than resolved into one answer (Gotcha 105, the 41-vs-43 rule reused).
    h.divergenceNote
      ? el('div.info-box', { style: 'margin:14px 0' },
        el('h4', {}, 'These two readings disagree, and both are right'),
        el('p', {}, h.divergenceNote))
      : null,

    // Whether a published index even exists for this mandate. NOT_HELD is this app's gap
    // (fixable by loading the index); NOT_SINGULAR is a property of the question - a thematic
    // fund has no single index to be measured against, ever. Two different facts (Gotcha 121).
    reading('Is there an index to compare it with?',
      verdictBadge(h.benchmarkAvailability, h.benchmarkAvailabilityLabel, h.benchmarkNote),
      h.benchmarkNote),

    ranked && h.survivorshipReason
      ? reading('How many of its peers did not survive',
        el('div', {},
          el('span', {}, h.survivorshipReason),
          missing(h.survivorshipAttritionPct) ? null
            : el('div.faint', { style: 'font-size:12px;margin-top:3px' },
              num(h.survivorshipAttritionPct, 1) + '% of the category gone since '
              + (h.survivorshipFrom || 'the start of the window')))
        // No caveat sentence here: `survivorshipCaveat` already carries it under the standing
        // this figure qualifies, two readings above. Saying it twice on one panel is what trains
        // a reader past the line that matters (Gotcha 117c, 132).
        )
      : null,

    el('p.faint', { style: 'font-size:12px;margin-top:16px' },
      [h.monthsOnFile ? h.monthsOnFile + ' month-end prices on file' : null,
        h.minMonthsToMeasure ? h.minMonthsToMeasure + ' needed to measure anything' : null,
        h.folio ? 'folio ' + h.folio : null,
        h.isin || null,
        h.amfiSchemeCode ? 'AMFI scheme ' + h.amfiSchemeCode : null].filter(Boolean).join(' · ')));

  // A verdict on the heading rather than a count - a count of 1 says nothing about a
  // single-verdict panel (SPEC 27.15.1). It routes through the shared badge, so an unmeasured
  // reading draws the striped marker rather than a blank.
  return withSummary(
    collapse(section(h.schemeName || h.fundName || 'Fund', '', body),
      { key: 'fund:' + (h.amfiSchemeCode || h.isin || h.schemeName) }),
    h.recordVerdictLabel || humanLabel(h.recordVerdict),
    { type: h.recordVerdict === 'AHEAD_OF_BENCHMARK' ? 'success'
      : h.recordVerdict === 'BEHIND_BENCHMARK' ? 'warning' : undefined });
}

// ---------------------------------------------------------------------- the archive

/**
 * How deep the record goes, with four outcomes that are never collapsed into two.
 *
 * "Fetched", "not published" (asked, and the file carried no equity cross-section - a finding),
 * "failed" (the only real gap) and absent from the table entirely (never attempted). All four
 * look identical downstream as an absence of NAV rows, and an absence of rows is exactly what the
 * survivorship ledger reads as a fund winding up (SPEC 52.7, Gotcha 142).
 */
function archivePanel() {
  if (!history) return empty('No archive on file', 'The month-end backfill has never run.');
  return el('div', {},
    el('div.grid.kpis', {},
      kpi({
        label: 'Months on record',
        value: num(history.monthsOnRecord, 0),
        sub: history.oldestMonthEndOnFile
          ? shortDate(history.oldestMonthEndOnFile) + ' to ' + shortDate(history.newestMonthEndOnFile)
          : 'nothing fetched yet',
      }),
      kpi({
        label: 'Failed',
        value: num(history.monthsFailed, 0),
        sub: 'the only count here that is a gap in the record',
        tone: history.monthsFailed > 0 ? 'warning' : 'neutral',
      }),
      kpi({
        label: 'Asked, nothing published',
        value: num(history.monthsNotPublished, 0),
        sub: 'every candidate business day was tried - a finding, not a gap',
        tone: 'neutral',
      }),
      kpi({
        label: 'Schemes deep enough to measure',
        value: num(history.schemesWithEnoughHistory, 0),
        sub: 'of ' + num(history.schemesPersisted, 0) + ', at '
          + num(history.minMonthsToMeasure, 0) + ' months or more',
        tone: 'neutral',
      })),
    history.monthsNeedingAWalkBack
      ? el('p.faint', { style: 'font-size:12.5px;margin-top:10px' },
        num(history.monthsNeedingAWalkBack, 0) + ' month'
        + (history.monthsNeedingAWalkBack === 1 ? ' end' : ' ends')
        + ' fell on a day the exchange was shut, so an earlier business day was used. A weekend '
        + 'month end returns a near-empty file, and taken at face value that would state that the '
        + 'whole equity universe had no price that month.')
      : null,
    history.caveat
      ? el('p.faint', { style: 'font-size:12px;margin-top:8px' }, history.caveat) : null);
}

const MONTH_COLS = [
  { key: 'month', label: 'Month', render: (m) => shortDate(m.month) },
  {
    key: 'status',
    label: 'Outcome',
    render: (m) => badge(m.status, {
      type: m.status === 'FETCHED' ? 'success' : m.status === 'FAILED' ? 'warning' : 'neutral',
      label: m.status === 'FETCHED' ? 'Fetched'
        : m.status === 'NOT_PUBLISHED' ? 'Asked, nothing published' : 'Failed',
    }),
  },
  {
    key: 'dateUsed',
    label: 'Date used',
    render: (m) => el('div', {},
      el('span', {}, m.dateUsed ? shortDate(m.dateUsed) : '—'),
      m.datesTried > 1
        ? el('div.faint', { style: 'font-size:12px' }, m.datesTried + ' days tried') : null),
  },
  { key: 'equityRows', label: 'Equity schemes in the file', align: 'r', render: (m) => num(m.equityRows, 0) },
  { key: 'note', label: 'Note', render: (m) => el('span.faint', {}, m.note || '—') },
];

// ---------------------------------------------------------------------- what the app can see

/** The denominators that keep everything above honest. */
function coveragePanel() {
  if (!coverage) return empty('No coverage figures', 'The daily NAV pull has never run.');
  const cats = coverage.screenableByCategory || {};
  // The server's own label, never humanLabel(): title-casing the enum printed "Elss" and
  // "Sectoral Thematic" at the reader. The fallback exists so a category added server-side
  // without a label still reads as words rather than as SHOUTING.
  const labels = coverage.categoryLabels || {};
  const rows = Object.keys(cats).map((k) => ({
    category: k,
    label: labels[k] || humanLabel(k),
    schemes: cats[k],
    // Ten is the floor below which no quartile is published, measured against the live
    // cross-section rather than assumed: Contra has four schemes in the entire market.
    rankable: cats[k] >= 10,
  }));

  return el('div', {},
    el('div.grid.kpis', {},
      kpi({ label: 'Schemes on file', value: num(coverage.schemesPersisted, 0), sub: 'from the daily AMFI file' }),
      kpi({
        label: 'Comparable schemes',
        value: num(coverage.screenableSchemes, 0),
        sub: 'Direct plan, Growth option, open-ended equity - one share class per scheme',
        tone: 'neutral',
      }),
      kpi({
        label: 'Deep enough to measure',
        value: num(coverage.schemesWithEnoughHistory, 0),
        sub: 'at ' + num(coverage.minPointsToMeasure, 0) + ' month-end prices or more',
        tone: 'neutral',
      }),
      kpi({
        label: 'Your funds unmatched',
        value: num(coverage.ownedFundsUnresolved, 0),
        sub: 'of ' + num(coverage.ownedFunds, 0) + ' - a fund we cannot match is reported, never dropped',
        tone: coverage.ownedFundsUnresolved > 0 ? 'warning' : 'neutral',
      })),

    // A category AMFI spells a way the app has no rule for would silently halve a peer group and
    // every percentile inside it. So it is named rather than folded into the nearest match, and
    // an empty list here is the ordinary state (B-096, SPEC 52.4).
    (coverage.unclassifiedHeaders || []).length
      ? alert({
        severity: 'WARNING',
        title: (coverage.unclassifiedHeaders || []).length + ' category headings this app has no rule for',
        message: (coverage.unclassifiedHeaders || []).join(' | ')
          + '. Schemes under these are on file but sit in no peer group, so nothing is ranked '
          + 'against them. They are named rather than matched to the nearest category, because a '
          + 'wrong peer group is worse than no peer group.',
      })
      : null,

    coverage.unparsedLinesLastRun
      ? alert({
        severity: 'WARNING',
        title: num(coverage.unparsedLinesLastRun, 0) + ' lines in the last AMFI file could not be read',
        message: 'The file format may have changed. This is counted rather than ignored so a '
          + 'layout change is loud instead of silently shrinking the universe.',
      })
      : null,

    el('h4', { style: 'margin:16px 0 6px' }, 'How many schemes are in each category'),
    el('p.faint', { style: 'font-size:12.5px;margin:0 0 8px' },
      'This is the peer group a fund is ranked inside. Below ten schemes no quartile is published '
      + 'at all - a percentile over four funds is arithmetic, not a finding.'),
    table([
      { key: 'label', label: 'Category' },
      { key: 'schemes', label: 'Comparable schemes', align: 'r', render: (r) => num(r.schemes, 0) },
      {
        key: 'rankable',
        label: 'Can a quartile be published?',
        render: (r) => (r.rankable
          ? badge('YES', { type: 'success', label: 'Yes' })
          : badge('TOO_FEW_PEERS', { type: 'neutral', label: 'Too few peers' })),
      },
    ], rows, { sortKey: 'schemes', filter: false }),

    (coverage.limits || []).length
      ? el('div.info-box', { style: 'margin-top:14px' },
        el('h4', {}, 'What these figures cannot tell you'),
        el('ul', {}, ...(coverage.limits || []).map((l) => el('li', {}, l))))
      : null,

    coverage.lastRunFailure
      ? alert({
        severity: 'WARNING',
        title: 'The last NAV pull reported a failure',
        message: String(coverage.lastRunFailure),
      })
      : null);
}

// ---------------------------------------------------------------------- paint

function paint() {
  const rows = (holdings && holdings.holdings) || [];
  const measured = (lens && lens.holdings) || [];
  const nodes = [];

  nodes.push(section('The funds you own',
    'Read from Zerodha Coin and priced at the last published net asset value. A fund has one '
    + 'price a day, struck after the market closes and published late in the evening, so a fund '
    + 'value is always at least one business day behind a share price - every NAV below carries '
    + 'the date it was struck.',
    moneyCards(),
    stanceBox(),
    rows.length
      ? table(HOLDING_COLS, rows, { filter: false })
      : empty('No funds on file',
        'Nothing has been synced from the broker. That means none have been read, not that none '
        + 'are held.')));

  // The coverage line goes with the sections it describes, and counts four states because the
  // rows beneath it draw four (B-117). Counting only "not measured" reported three of six
  // holdings as measured when exactly one carried a verdict.
  nodes.push(withCount(section('What the record says about each one',
    'One fund at a time: what its plan costs, how often it beat its own category over rolling '
    + 'three-year windows, and where it sat inside that category. Nothing here is a suggestion '
    + 'to do anything, and none of it adds a point to any score anywhere in the app.',
    lens && lens.coverageLine
      ? el('p.muted', { style: 'font-size:13px;margin:0 0 12px' }, lens.coverageLine) : null,
    measured.length
      ? el('div', {}, ...measured.map(fundSection))
      : empty('Nothing to measure yet',
        'No fund holding could be matched to a scheme with month-end prices on file.')),
  measured.filter((h) => h.recordVerdict !== 'NOT_MEASURED'
    && h.recordVerdict !== 'NOT_COMPARABLE').length));

  nodes.push(withSummary(collapse(section('How deep this app’s record goes',
    'Every figure above is computed from month-end prices this app fetched and stored. This is '
    + 'how many months it has, and what happened in the ones it does not.',
    archivePanel(),
    history && (history.months || []).length
      ? el('div', { style: 'margin-top:16px' },
        el('h4', { style: 'margin:0 0 6px' }, 'Month by month'),
        table(MONTH_COLS, history.months, { sortKey: 'month', sortDir: 'desc', filter: false }))
      : null),
  { key: 'funds:archive' }),
  history ? num(history.monthsOnRecord, 0) + ' months' : null, { type: 'info' }));

  nodes.push(withSummary(collapse(section('What this app can see of the fund market',
    'The denominators behind everything above. A peer comparison is only as good as the peer '
    + 'group, so this names how many schemes are in each one and which ones are too small to '
    + 'rank inside at all.',
    coveragePanel()), { key: 'funds:coverage' }),
  coverage ? num(coverage.screenableSchemes, 0) + ' schemes' : null, { type: 'info' }));

  mount(view, nodes);
}

// ---------------------------------------------------------------------- boot

async function reload() {
  // `.data` is not optional. api.js get() returns an envelope - {data, stale, cachedAt} - and
  // assigning it straight through leaves every field undefined, so the page renders its section
  // shells with nothing inside them: HTTP 200 on the page, on every module and on every
  // endpoint, the syntax checker clean, and a blank screen (SPEC 51, Gotcha 137).
  const [p, hold, hist, cov] = await Promise.all([
    get('/api/mf/portfolio').then((r) => r.data),
    get('/api/mf/holdings').then((r) => r.data),
    get('/api/mf/history').then((r) => r.data),
    get('/api/mf/coverage').then((r) => r.data),
  ]);
  lens = p;
  holdings = hold;
  history = hist;
  coverage = cov;
  paint();
}

async function boot() {
  view.replaceChildren(el('section.section', {},
    el('h2.section-title', {}, 'Loading funds…'), skeleton(4)));
  await initChrome();
  await reload();
}

boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  const msg = err instanceof OfflineError
    ? 'The dashboard could not reach the app. Is it running?'
    : String(err && err.message ? err.message : err);
  mount(view, section('Something went wrong', '', empty('Could not load funds', msg)));
});
