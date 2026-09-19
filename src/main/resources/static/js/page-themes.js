/**
 * Themes — the emerging sectors the government is funding, and how much of each this app can
 * actually see (SPEC 51).
 *
 * Why this page exists, and why it is a coverage screen rather than a stock list.
 *
 * The question that produced it was "are we tracking the semiconductor, water and AI data-centre
 * names?" — and before this page there was nowhere that question could even be asked. NSE
 * classifies the market into 22 macro buckets and none of them is any of those three, so a chip
 * assembler read "Capital Goods" and sat indistinguishable from a bearing maker. A theme column
 * alone would not have answered it either: a column can only describe stocks already in the
 * screening universe, so a theme nobody screens renders as an empty column and reads like a
 * theme with no companies in it. That is Gotcha 44 in its most flattering form. The only honest
 * answer counts the businesses the app does NOT reach, which is what this page leads with.
 *
 * Four things here are deliberate.
 *
 * THREE STATES, NEVER TWO. A tagged business is analysed, or it is a confirmed listing the
 * universe does not reach, or its ticker could not be confirmed here at all. The third leaves
 * the coverage percentage entirely rather than counting either way, because folding it in is how
 * a typo would inflate a coverage figure (SPEC 51.3).
 *
 * A THEME IS NOT A SCORE, AND THE PAGE SAYS SO REPEATEDLY. Government money is a demand signal
 * with a political dependency, never a quality signal — subsidised industries have destroyed
 * capital in this country before. Every theme carries the specific reason it could disappoint,
 * on screen and not in a tooltip, because the reader most likely to act on a theme list is the
 * one least likely to hunt for its caveat.
 *
 * THE COVERAGE FIGURE CARRIES ITS OWN DENOMINATOR. "8 of 8 covered" would otherwise read as "the
 * sector is fully researched", when all it says is that eight names someone typed into a file
 * are in the universe. The map's own completeness is unmeasured and unmeasurable — there is no
 * published census of "every Indian semiconductor stock" to check it against.
 *
 * AND THE GAP LIST IS THE ACTION, not the stock table. A gap means no pillar has ever been
 * measured on that business. Closing it means the app will analyse it; it is not a suggestion to
 * buy it, and the wording never implies otherwise.
 *
 * Page-load requests, both verified DB-only by hand — a page-load get() is ungated and only
 * getOnDemand() has the allowlist (Gotcha 39):
 *   GET /api/themes             coverage, members and the latest screening scores
 *   GET /api/themes/gaps        the confirmed listings the universe does not reach
 *   GET /api/themes/candidates  businesses the map may be missing, with the evidence
 *
 * AND THE MAP AGES SILENTLY, which is the reason for the vintage line and the candidates
 * section. Nothing in this app can update universe-themes.csv - no feed publishes it, no job
 * writes it - so it is exactly as current as the last person to edit it. That failure is
 * flattering rather than obvious: every figure above is counted against the map's own
 * denominator, so a map that stops growing while the market does not keeps reporting high
 * coverage of a shrinking list. The gap list answers "the map names a business we do not
 * reach"; the candidates list answers the half that actually decays, "the market has a
 * business the map has never named" (SPEC 51.9, 51.10).
 */

import { get, OfflineError } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters } from './filters.js';
import {
  el, section, collapse, withCount, withSummary, table, kpi, empty, skeleton, mount, badge,
  unmeasured,
} from './ui.js';
import { displaySymbol, shortDate, num } from './format.js';
import { THEME_SHORT, THEME_STATUS } from './theme-cells.js';
import { analystCoverageCol, analystCoverageLine } from './analyst-cells.js';

const view = document.getElementById('view');

// ---------------------------------------------------------------------- filters

const GAP_FILTERS = chipFilters([
  {
    label: 'Theme:',
    key: 'theme',
    options: (rows) => {
      const present = [];
      (rows || []).forEach((r) => { if (!present.includes(r.theme)) present.push(r.theme); });
      return [{ value: 'ALL', text: 'All' }].concat(present.map((t) => ({
        value: t,
        text: THEME_SHORT[t] || t,
        test: (r) => r.theme === t,
      })));
    },
  },
  { search: true, placeholder: 'Find a company…' },
], () => paint({ keepFocus: true }));

const CANDIDATE_FILTERS = chipFilters([
  {
    label: 'Found in:',
    key: 'lane',
    options: () => [
      { value: 'ALL', text: 'All' },
      { value: 'NEW', text: 'Recent listings', test: (r) => r.lane === 'NEW_LISTING' },
      { value: 'OLD', text: 'Already listed', test: (r) => r.lane === 'LISTED_NAME' },
    ],
  },
  {
    label: 'Cost:',
    key: 'cost',
    options: () => [
      { value: 'ALL', text: 'All' },
      { value: 'FREE', text: 'Already screened', test: (r) => r.screened === true },
      { value: 'NEW', text: 'Not screened', test: (r) => r.screened !== true },
    ],
  },
  { search: true, placeholder: 'Find a company…' },
], () => paint({ keepFocus: true }));

// ---------------------------------------------------------------------- pieces

/**
 * The headline. Four tiles, and the middle one is the answer to the question.
 *
 * `notScreened` is the tile that matters and it is toned as a warning even at zero, because a
 * green "0 gaps" would be read as a score rather than as a state.
 */
function summaryCards(s) {
  if (!s) return null;
  const pct = s.coveragePercent;
  return el('div.grid.kpis', {},
    kpi({
      label: 'Themes tracked',
      value: num(s.themes, 0),
      sub: s.distinctCompanies + ' businesses named across them',
    }),
    kpi({
      label: 'Analysed by this app',
      value: num(s.screened, 0),
      sub: pct === null || pct === undefined ? 'no confirmable members' : num(pct, 0) + '% of confirmable names',
      tone: 'neutral',
    }),
    kpi({
      label: 'Named but not screened',
      value: num(s.notScreened, 0),
      sub: 'no pillar has ever been measured on these',
      tone: s.notScreened > 0 ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Ticker unconfirmed',
      value: num(s.unverified, 0),
      sub: 'outside NSE index lists — excluded from the percentage',
      tone: 'neutral',
    }));
}

/** The standing "what this means" box. SPEC 21 — the reader is not a market professional. */
function stanceBox(s) {
  return el('div.info-box', {},
    el('h4', {}, 'What a theme here means, and what it does not'),
    el('p', {},
      'A theme says the Government of India is putting money or protection behind an area — a '
      + 'mission, a production-linked incentive, an import duty. Every row names the specific '
      + 'scheme, so you can disagree with the claim rather than take it on trust.'),
    el('p', {},
      el('strong', {}, 'It is a demand signal, never a quality signal. '),
      'Subsidised industries have destroyed plenty of capital in India before. Government support '
      + 'can create an order book and can also be withdrawn, and it says nothing about whether a '
      + 'particular company earns a decent return on the capital it employs. Nothing on this page '
      + 'adds a single point to any score anywhere in the app.'),
    el('p', {},
      el('strong', {}, 'Being "analysed" is not approval. '),
      'It means the business is in the screening universe, so the seven pillars run on it like '
      + 'they do on everything else. Several of these names will screen badly, and that is the '
      + 'system working.'),
    el('p.faint', {},
      s && s.caveat ? s.caveat : ''));
}

/** Status badge for one member. Three states, rendered as three visibly different things. */
function statusBadge(status) {
  const spec = THEME_STATUS[status];
  if (!spec) return unmeasured('Unrecognised status');
  const node = badge(status, spec);
  node.title = status === 'SCREENED'
    ? 'In the screening universe, so every pillar runs on it in the ordinary way.'
    : status === 'NOT_SCREENED'
      ? 'A confirmed NSE listing the screening universe does not reach — no pillar has ever been '
        + 'measured on it here.'
      : 'Outside NSE index constituent lists, so this app could not confirm the ticker. Counted '
        + 'in neither half of the coverage figure rather than assumed either way.';
  node.setAttribute('data-no-gloss', '');
  return node;
}

const MEMBER_COLS = [
  {
    key: 'symbol',
    label: 'Company',
    render: (r) => el('a', { href: 'stock.html?symbol=NSE:' + encodeURIComponent(r.symbol) },
      displaySymbol(r.symbol)),
  },
  { key: 'status', label: 'Status', render: (r) => statusBadge(r.status) },
  { key: 'role', label: 'Role in the chain', render: (r) => el('span', {}, r.role || '—') },
  { key: 'policy', label: 'Scheme', render: (r) => el('span.faint', {}, r.policy || '—') },
  {
    key: 'held',
    label: 'Owned',
    render: (r) => (r.held ? badge('HELD', { type: 'info', label: 'You own it' }) : el('span.faint', {}, '—')),
  },
];

const SCORE_COLS = [
  {
    key: 'symbol',
    label: 'Company',
    render: (r) => el('a', { href: 'stock.html?symbol=NSE:' + encodeURIComponent(r.symbol) },
      displaySymbol(r.symbol)),
  },
  {
    key: 'compositeScore',
    label: 'Score',
    // Null, never zero: a member screened at some point but absent from the latest run has an
    // unknown score, and a 0 would sort it to the bottom as though it had been judged and failed.
    render: (r) => (r.compositeScore === null || r.compositeScore === undefined
      ? unmeasured('Not in the latest screening run.')
      : el('span', {}, num(r.compositeScore, 0))),
  },
  { key: 'verdict', label: 'Verdict', render: (r) => (r.verdict ? badge(r.verdict) : unmeasured('Not in the latest run.')) },
  { key: 'grade', label: 'Grade', render: (r) => el('span', {}, r.grade || '—') },
  { key: 'role', label: 'Role in the chain', render: (r) => el('span', {}, r.role || '—') },
  // Who else is watching these (SPEC 51.8). Same renderer as the portfolio, screener, watchlist
  // and discovery — one cell, nothing to drift (Gotcha 85). The ledger was already market-wide;
  // this surfaces coverage the app had and was not showing beside the themes.
  analystCoverageCol(),
  {
    key: 'screeningDate',
    label: 'As of',
    render: (r) => el('span.faint', {}, r.screeningDate ? shortDate(r.screeningDate) : '—'),
  },
];

/** One theme: its caution, its coverage, its members and how the screened ones are scoring. */
function themeSection(t) {
  const c = t.coveragePercent;
  const head = el('div', {},
    el('p', {}, el('strong', {}, 'Policy: '), t.policy),
    el('p.muted', { style: 'font-size:13px' }, t.caution));

  const counts = el('div.muted', { style: 'font-size:12.5px;margin:8px 0' },
    t.screened + ' analysed'
    + (t.notScreened ? ', ' + t.notScreened + ' named but not screened' : '')
    + (t.unverified ? ', ' + t.unverified + ' ticker unconfirmed' : '')
    + (t.held ? ', ' + t.held + ' owned' : '')
    + (c === null || c === undefined ? '' : ' — ' + num(c, 0) + '% coverage'));

  const body = el('div', {}, head, counts,
    el('h4', { style: 'margin:14px 0 6px' }, 'How the ones we analyse are scoring'),
    (t.screenedRows && t.screenedRows.length
      ? el('div', {},
        table(SCORE_COLS, t.screenedRows, { sortKey: 'compositeScore', filter: false }),
        // Mandatory beside the Analysts column (Gotcha 44): a column of "None on file" must not
        // read as "the market has no view on these" when this ledger is thin by construction
        // (SPEC 49.7). Over the rows it sits under, never a wider set (B-098).
        analystCoverageLine(t.screenedRows, { noun: 'businesses' }))
      : empty('None analysed yet',
        'Every business named in this theme is either outside the screening universe or its '
        + 'ticker could not be confirmed. Nothing here has been measured.')),
    el('h4', { style: 'margin:18px 0 6px' }, 'Everything this app names in the theme'),
    table(MEMBER_COLS, t.members, { sortKey: 'status', sortDir: 'asc', filter: false }),
    el('p.faint', { style: 'font-size:12px;margin-top:8px' }, t.caveat));

  // A verdict on the heading rather than a count, because the question this section answers is
  // "how much of it can we see" and a member count says nothing about that (SPEC 27.15.1).
  const label = (c === null || c === undefined) ? 'No coverage figure' : num(c, 0) + '% covered';
  const tone = (c === null || c === undefined) ? undefined
    : (c >= 80 ? 'success' : c >= 50 ? 'warning' : 'danger');
  return withSummary(
    collapse(section(t.label, '', body), { key: 'theme:' + t.theme }),
    label, { type: tone });
}

const GAP_COLS = [
  {
    key: 'symbol',
    label: 'Company',
    render: (r) => el('a', { href: 'stock.html?symbol=NSE:' + encodeURIComponent(r.symbol) },
      displaySymbol(r.symbol)),
  },
  {
    key: 'theme',
    label: 'Theme',
    render: (r) => badge(r.theme, { type: 'neutral', label: THEME_SHORT[r.theme] || r.theme }),
  },
  { key: 'role', label: 'Role in the chain', render: (r) => el('span', {}, r.role || '—') },
  { key: 'policy', label: 'Scheme', render: (r) => el('span.faint', {}, r.policy || '—') },
];

/**
 * When the map was last read against current policy.
 *
 * Shown on the screen that depends on it, not buried on the health page, because every figure
 * above is counted against this file's own list. Past the budget cycle it says so - and it never
 * calls it a fault, because nothing schedules this file (Gotcha 125).
 */
function vintageNote(s) {
  if (!s) return null;
  if (!s.reviewedOn) {
    return el('p.faint', { style: 'font-size:12px;margin-top:10px' },
      'This map carries no review date, so there is no way to tell how old its policy claims are.');
  }
  const days = s.reviewDaysAgo;
  const stale = typeof days === 'number' && days > 180;
  const line = 'Map last read against current policy on ' + shortDate(s.reviewedOn)
    + (typeof days === 'number' ? ' (' + num(days, 0) + ' days ago)' : '')
    + (s.policyAsOf ? ' — written against ' + s.policyAsOf + '.' : '.');
  if (!stale) {
    return el('p.faint', { style: 'font-size:12px;margin-top:10px' }, line);
  }
  return el('div.info-box', { style: 'margin-top:12px' },
    el('h4', {}, 'This map is due a re-read'),
    el('p', {}, line),
    el('p', {},
      'Nothing in this app can update it, so it is as current as the last edit. Note which way '
      + 'that fails: the coverage figures above are counted against the list in this very file, '
      + 'map that stopped growing while the market did not keeps reporting high coverage rather '
      + 'than a falling number. The candidates section below is a starting point.'));
}

const CANDIDATE_COLS = [
  {
    key: 'symbol',
    label: 'Company',
    render: (r) => el('div', {},
      el('a', { href: 'stock.html?symbol=NSE:' + encodeURIComponent(r.symbol) },
        displaySymbol(r.symbol)),
      r.companyName ? el('div.faint', { style: 'font-size:12px' }, r.companyName) : null),
  },
  {
    key: 'themeLabel',
    label: 'Suggested theme',
    render: (r) => badge(r.theme, { type: 'neutral', label: THEME_SHORT[r.theme] || r.themeLabel }),
  },
  {
    // The evidence, in the row. A reader who can see WHY it was proposed can reject it in one
    // glance; a bare list of tickers has to be trusted or ignored wholesale.
    key: 'matched',
    label: 'Because the name says',
    render: (r) => el('span', {}, r.matched ? '“' + r.matched + '”' : '—'),
  },
  {
    key: 'lane',
    label: 'Found in',
    render: (r) => (r.lane === 'NEW_LISTING'
      ? el('span', {}, 'Listed ' + (r.listingDate ? shortDate(r.listingDate) : 'recently'))
      : el('span.faint', {}, 'Already listed')),
  },
  {
    key: 'industry',
    label: 'NSE industry',
    // Absent for almost every recent listing, and that absence is a fact about NSE's index
    // constituent lists rather than about the company - so a dash, never "not measured".
    render: (r) => el('span.faint', {}, r.industry || '—'),
  },
  {
    key: 'screened',
    label: 'Cost to add',
    render: (r) => (r.screened
      ? badge('FREE', { type: 'success', label: 'Already screened' })
      : badge('NEW', { type: 'neutral', label: 'New symbol' })),
  },
];

/** The candidates section: what the map may be missing, and how much of it this could see. */
function candidateSection(c) {
  const rows = (c && c.candidates) || [];
  const shown = CANDIDATE_FILTERS.apply(rows);
  const body = el('div', {},
    el('div.info-box', {},
      el('h4', {}, 'These are questions, not tags'),
      el('p', {},
        'Nothing here has been added to the map and nothing has been screened. Each row says '
        + 'which word matched which company name, so you can reject most of them at a glance. '
        + 'Accepting one means adding a line to universe-themes.csv by hand — which starts the '
        + 'app analysing the business, and says nothing about whether it is worth owning.'),
      c && c.recall ? el('p', {}, el('strong', {}, 'How much this can see: '), c.recall) : null,
      c && c.themesWithoutHint && c.themesWithoutHint.length
        ? el('p.faint', {},
          'Finds nothing for ' + c.themesWithoutHint.join(', ')
          + ' — no company name is diagnostic for those, and a hint that fired on every '
          + 'pharmaceutical name would not be evidence.')
        : null),
    CANDIDATE_FILTERS.bar(rows, shown.length, 'suggestions'),
    (shown.length
      ? table(CANDIDATE_COLS, shown, { filter: false })
      : empty('Nothing suggested',
        'No unmapped company name matched a theme hint. Read that as "most companies are not '
        + 'named after what they do" rather than as "nothing is missing".')),
    c && c.caveat ? el('p.faint', { style: 'font-size:12px;margin-top:8px' }, c.caveat) : null);

  return withCount(collapse(section('Possibly missing from the map',
    'Businesses whose name suggests a funded theme and which the map has never named. The other '
    + 'half of the gap list: that one finds a business we named and do not analyse, this one '
    + 'looks for a business we never named at all.',
    body), { key: 'themes:candidates' }), shown.length);
}

// ---------------------------------------------------------------------- paint

let data = null;
let gaps = null;
let candidates = null;

function paint(opts = {}) {
  const s = data && data.summary;
  const themes = (data && data.themes) || [];
  const gapRows = (gaps && gaps.gaps) || [];
  const shown = GAP_FILTERS.apply(gapRows);

  const nodes = [];

  nodes.push(section('Emerging themes the government is funding',
    'Which policy-backed areas this app can see, and which businesses in them it has never '
    + 'looked at.',
    summaryCards(s),
    stanceBox(s),
    vintageNote(s)));

  nodes.push(withCount(collapse(section('Named but never screened',
    'Confirmed NSE listings in a funded theme that the screening universe does not reach, so no '
    + 'pillar has ever been measured on them. This is the list worth acting on — adding one means '
    + 'the app will analyse it, which is not the same as a reason to buy it.',
    GAP_FILTERS.bar(gapRows, shown.length, 'companies'),
    (shown.length
      ? table(GAP_COLS, shown, { sortKey: 'theme', sortDir: 'asc', filter: false })
      : empty('Nothing outstanding',
        'Every confirmed business in the theme map is in the screening universe.')),
    (gaps && gaps.note ? el('p.faint', { style: 'font-size:12px;margin-top:8px' }, gaps.note) : null)),
  { key: 'themes:gaps' }), shown.length));

  nodes.push(candidateSection(candidates));

  themes.forEach((t) => nodes.push(themeSection(t)));

  mount(view, nodes);

  // Typing in the gap filter re-paints the page, so the box must not lose the cursor on every
  // keystroke — the section it lives in is re-mounted whole (SPEC 27.15.1).
  if (opts.keepFocus) {
    const box = view.querySelector('input[type="search"]');
    if (box) {
      box.focus();
      box.setSelectionRange(box.value.length, box.value.length);
    }
  }
}

// ---------------------------------------------------------------------- boot

async function reload() {
  // `.data` is not optional. api.js `get()` returns an envelope — {data, stale, cachedAt} — and
  // assigning it straight through leaves every field undefined, so the page renders its section
  // shells and nothing inside them. HTTP 200 on the page, 200 on every module, 200 on both
  // endpoints, the syntax checker clean, the tests green, and a blank screen (Gotcha 41/82/89).
  // Caught by looking at a screenshot, which is the only thing that would have.
  const [overview, gapList, candidateList] = await Promise.all([
    get('/api/themes').then((r) => r.data),
    get('/api/themes/gaps').then((r) => r.data),
    get('/api/themes/candidates?months=36').then((r) => r.data),
  ]);
  data = overview;
  gaps = gapList;
  candidates = candidateList;
  paint();
}

async function boot() {
  view.replaceChildren(el('section.section', {},
    el('h2.section-title', {}, 'Loading themes…'), skeleton(3)));
  await initChrome();
  await reload();
}

boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  const msg = err instanceof OfflineError
    ? 'The dashboard could not reach the app. Is it running?'
    : String(err && err.message ? err.message : err);
  mount(view, section('Something went wrong', '', empty('Could not load themes', msg)));
});
