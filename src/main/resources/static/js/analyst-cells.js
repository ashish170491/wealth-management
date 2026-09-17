/**
 * Shared renderers for the analyst target ledger (SPEC 49.8).
 *
 * One file, used by the Track Record page and the stock page, for the reason B-099 exists: a fix
 * that lands on one screen has to be walked to every screen that shares the data, and the way to
 * stop needing to remember that is a shared renderer rather than a shared payload.
 *
 * Nothing is decided here. Every verdict, status and refusal arrives already computed from Java,
 * where it is unit-tested; this file picks a wording and a colour. Two things it must never do:
 * present a target as a recommendation (the vocabulary has no word for "buy", deliberately —
 * SPEC 20 rule 10), and render an unmeasured figure as a number (SPEC 21 rule 7).
 */
import { el, badge, unmeasured, kpi, table } from './ui.js';
import {
  inr, pct, num, shortDate, missing, NOT_MEASURED, humanLabel,
  displaySymbol, stockHref,
} from './format.js';

/** Status -> how it reads and how it looks. Plain words (SPEC 21 rule 3). */
export const TARGET_STATUS = {
  REACHED: { type: 'success', label: 'Price got there' },
  MISSED: { type: 'warning', label: 'Ran out of time' },
  PENDING: { type: 'info', label: 'Still running' },
  SUPERSEDED: { type: 'neutral', label: 'Revised since' },
  UNPRICED: { type: 'unmeasured', label: NOT_MEASURED },
};

/** What each status means, in a sentence, for the tooltip and the guide. */
export const STATUS_HELP = {
  REACHED: 'The share price touched this target at some point after the call. It may not have stayed there.',
  MISSED: 'The horizon ran out and the price never touched the target.',
  PENDING: 'The horizon has not run out yet. It counts as neither a hit nor a miss until it does.',
  SUPERSEDED: 'The same brokerage published a new target before this one ran its course, so the claim was withdrawn. It is not counted as a miss, but it is counted as a revision.',
  UNPRICED: 'The closing price on the day of the call could not be recovered, so there is nothing to measure this against yet.',
};

/** Sort order: resolved first (they carry information), then running, then unmeasurable. */
export function statusRank(row) {
  const s = row && row.status;
  if (s === 'REACHED') return 0;
  if (s === 'MISSED') return 1;
  if (s === 'PENDING') return 2;
  if (s === 'SUPERSEDED') return 3;
  return 4;
}

export function statusCell(row) {
  const s = row && row.status;
  if (!s || s === 'UNPRICED') return unmeasured(STATUS_HELP.UNPRICED);
  const spec = TARGET_STATUS[s];
  if (!spec) return unmeasured('Unrecognised status');
  const node = badge(s, { type: spec.type, label: spec.label });
  node.title = STATUS_HELP[s] || '';
  return node;
}

/** BUY / HOLD / SELL as the house stated it — never as anything this app recommends. */
export function ratingCell(row) {
  const r = row && row.rating;
  if (!r || r === 'NOT_STATED') {
    // A headline with no rating has not said "hold". Inventing a neutral opinion for every
    // house that published only a number is the same error as rendering a null as a zero.
    return unmeasured('The headline quoted a target but no rating');
  }
  const type = r === 'BUY' ? 'success' : r === 'SELL' ? 'warning' : 'info';
  const node = badge(r, { type, label: r.charAt(0) + r.slice(1).toLowerCase() });
  node.title = 'What the brokerage said. This app does not endorse it and scores nothing from it.';
  return node;
}

/** The target, with the upside it implied on the day it was made. */
export function targetCell(row) {
  if (!row || missing(row.targetPrice)) return unmeasured('No figure');
  const wrap = el('span', {}, inr(row.targetPrice, { abbreviate: false }));
  if (!missing(row.upsidePctAtCall)) {
    wrap.append(el('span.muted', {}, ` (${pct(row.upsidePctAtCall)} then)`));
    wrap.title = `Target ${inr(row.targetPrice, { abbreviate: false })} against `
      + `${inr(row.priceAtCall, { abbreviate: false })} on the day of the call.`;
  }
  return wrap;
}

/**
 * How the stock did against the Nifty over the same dates.
 *
 * This is the column that matters. Whether a target was reached is the analyst's own scoreboard
 * and it flatters everyone in a rising market; this one subtracts the market.
 */
export function excessCell(row) {
  if (!row || missing(row.excessReturnPct)) {
    return unmeasured('Not measured yet — this needs both the stock and the index priced on the same two dates');
  }
  const v = row.excessReturnPct;
  const node = el('span.num' + (v > 0 ? '.positive' : v < 0 ? '.negative' : ''), {}, pct(v));
  node.title = `The stock did ${pct(row.returnPct)} while the Nifty 50 did `
    + `${pct(row.niftyReturnPct)} over the same dates.`;
  return node;
}

/** Days from the call to the day the price first touched the target. */
export function daysToReachCell(row) {
  if (!row || missing(row.daysToReach)) {
    return row && row.status === 'REACHED' ? unmeasured('Reached, but the date was not recorded')
      : el('span.muted', {}, '—');
  }
  return el('span.num', {}, num(row.daysToReach) + ' d');
}

/**
 * The horizon, and whether anybody actually said it.
 *
 * An assumed twelve months must never read as something the analyst stated. B-057 and B-097 were
 * each exactly this mistake one table over.
 */
export function horizonCell(row) {
  if (!row || missing(row.horizonDays)) return unmeasured('No horizon');
  const months = Math.round(row.horizonDays / 30.44);
  const node = el('span', {}, `${months} mo`);
  if (row.horizonStated) {
    node.title = 'The headline stated this horizon.';
  } else {
    node.append(el('span.muted', {}, ' (assumed)'));
    node.title = 'The headline did not state a horizon. Indian sell-side targets are conventionally '
      + 'twelve months, so that is what this app measures against — an assumption, not a statement.';
  }
  return node;
}

/** Hit rate, or the explicit refusal when a house has too few resolved calls. */
export function hitRateCell(house) {
  if (!house || missing(house.hitRatePercent)) {
    return unmeasured(`Fewer than the minimum number of calls have run their course. `
      + `A record of two-from-two is not a record.`);
  }
  return el('span.num', {}, pct(house.hitRatePercent, { signed: false }));
}

export function shortHeadline(row, max = 90) {
  const h = (row && row.headline) || '';
  if (!h) return el('span.muted', {}, '—');
  const text = h.length <= max ? h : h.slice(0, max - 1) + '…';
  const node = row.sourceUrl
    ? el('a', { href: row.sourceUrl, target: '_blank', rel: 'noopener noreferrer' }, text)
    : el('span', {}, text);
  node.title = h + (row.sourceName ? `\n— ${row.sourceName}` : '');
  return node;
}

export function issuedCell(row) {
  return el('span', {}, shortDate(row && row.issuedOn));
}

/**
 * The paragraph that must sit above any of these tables.
 *
 * It is not boilerplate. The sample is the desks that publish into this feed, weighted heavily
 * towards the handful that publish most, and it is roughly four-fifths Buy. A reader who does not
 * know that will read a hit rate as a fact about a brokerage's research — and worse, will read a
 * house that revises most of its targets before they fall due as unusually accurate.
 */
export function caveatBlock(caveat) {
  if (!caveat) return null;
  // Render whatever the server sends, in the order it sends it. A hard-coded key list here
  // silently drops any caveat added later -- and the one thing this block must never do is
  // quietly say less than the server intended (the B-098 shape: a renderer pinned to a schema
  // that has since moved).
  // `info-box` is the SPEC 21 "what this means" box. This asked for `.callout` and `ul.plain`,
  // and app.css defines NEITHER -- so the caveat SPEC 49.9 requires beside every one of these
  // tables rendered as unstyled body text and read as filler (B-112). A class that does not
  // exist fails exactly like Gotcha 104's missing variable: silently, attribute present.
  const list = el('ul');
  for (const [, text] of Object.entries(caveat)) {
    if (typeof text === 'string' && text.trim()) list.append(el('li', {}, text));
  }
  if (!list.firstChild) return null;
  return el('div.info-box', {}, list);
}


// ---------------------------------------------------- what would have to be true (SPEC 49.13)

/**
 * How each plausibility verdict reads and looks.
 *
 * The colours grade how DEMANDING the target is, never whether to act on it. A green
 * "in line with its record" does not mean the target will be reached -- it means the target asks
 * the business for more of what it has already been doing, which is the least demanding thing a
 * target can ask. Everything here is decided in Java (`AnalystTargetPlausibility`); this picks a
 * word and a colour.
 */
const PLAUSIBILITY = {
  BELOW_ITS_RECORD: { label: 'Less than it has been doing', type: 'success' },
  IN_LINE_WITH_RECORD: { label: 'In line with its record', type: 'success' },
  ABOVE_ITS_RECORD: { label: 'Above its record', type: 'warning' },
  FAR_ABOVE_ITS_RECORD: { label: 'Far above its record', type: 'danger' },
  NO_RECORD_TO_COMPARE: { label: 'No record to compare', type: 'neutral' },
  BEYOND_MODEL_RANGE: { label: 'Outside the model', type: 'neutral' },
  NOT_MEASURED: { label: NOT_MEASURED, type: 'unmeasured' },
};

/** The verdict badge on its own, for a table cell. */
export function plausibilityCell(row) {
  const v = row && row.verdict;
  const spec = PLAUSIBILITY[v];
  if (!v || !spec || v === 'NOT_MEASURED') {
    return unmeasured((row && row.reason) || 'No valuation model on file for this stock');
  }
  return el('span.badge.' + spec.type, { title: row.reason || '' }, spec.label);
}

/** A growth rate, or the explicit unmeasured marker -- never a zero (SPEC 21 rule 7). */
function growth(value) {
  return missing(value) ? unmeasured('Not measured for this stock')
    : el('span.num', {}, pct(value, { signed: false }));
}

/**
 * The "what would have to be true" panel.
 *
 * This is the answer to the question the composite CANNOT answer. SPEC 49.12 measured the
 * correlation between a house's claimed upside and this app's composite at -0.322, because half
 * the composite is price behaviour -- so a high score can never corroborate a target. A required
 * growth rate can, because it is a claim about earnings that the company's own record either
 * supports or does not.
 */
export function plausibilityBlock(p) {
  if (!p) return null;
  const spec = PLAUSIBILITY[p.verdict] || PLAUSIBILITY.NOT_MEASURED;
  const business = p.business || {};

  const head = el('div.grid.kpis', {},
    kpi({
      label: 'The target needs, each year',
      value: missing(p.requiredGrowthPercent) ? NOT_MEASURED : pct(p.requiredGrowthPercent, { signed: false }),
      raw: p.requiredGrowthPercent,
      tone: missing(p.requiredGrowthPercent) ? 'unmeasured' : 'neutral',
      sub: missing(p.atMedianTarget) ? 'no open target' : `at the median target of ${inr(p.atMedianTarget)}`,
    }),
    kpi({
      label: "Today's price already assumes",
      value: missing(p.impliedGrowthPercent) ? NOT_MEASURED : pct(p.impliedGrowthPercent, { signed: false }),
      raw: p.impliedGrowthPercent,
      tone: missing(p.impliedGrowthPercent) ? 'unmeasured' : 'neutral',
      sub: p.asOf ? `screened ${shortDate(p.asOf)}` : 'not screened',
    }),
    kpi({
      label: 'It has actually delivered',
      value: missing(p.historicalGrowthPercent) ? NOT_MEASURED : pct(p.historicalGrowthPercent, { signed: false }),
      raw: p.historicalGrowthPercent,
      tone: missing(p.historicalGrowthPercent) ? 'unmeasured' : 'neutral',
      sub: missing(p.yearsOfRecord) || !p.yearsOfRecord ? 'no annual accounts on file'
        : `over ${num(p.yearsOfRecord)} year${p.yearsOfRecord === 1 ? '' : 's'} of accounts`,
    }));

  // The other things that would have to be true, shown RAW beside the growth requirement rather
  // than folded into a second score (Gotcha 113c). Growth is one input; a demanding target on a
  // company with clean accounts and a long record is a different proposition from the same
  // target on one without, and that judgement stays with the reader.
  const checks = el('div.row.wrap', { style: 'margin-top:12px; gap:14px' },
    el('span', {}, el('span.muted', {}, 'Also true of the business: '),
      business.financialQualityVerdict
        ? badge(business.financialQualityVerdict, { label: humanLabel(business.financialQualityVerdict) })
        : unmeasured('Balance-sheet quality not measured')),
    el('span', {}, el('span.muted', {}, 'Red flags: '),
      business.forensicFlags
        ? el('span.badge.warning', { title: business.forensicFlags }, business.forensicFlags.split(';')[0])
        : el('span.muted', {}, 'none recorded')),
    el('span', {}, el('span.muted', {}, 'Our score: '),
      missing(business.compositeScore) ? unmeasured('Never screened')
        : el('span.num', {}, num(business.compositeScore))));

  const caveatList = el('ul');
  for (const [, text] of Object.entries(p.caveat || {})) {
    if (typeof text === 'string' && text.trim()) caveatList.append(el('li', {}, text));
  }

  return el('div', {},
    el('div.row.wrap', { style: 'gap:10px; margin-bottom:10px' },
      el('b', {}, 'What would have to be true'),
      el('span.badge.' + spec.type, {}, spec.label)),
    head,
    p.reason ? el('p', { style: 'margin-top:10px' }, p.reason) : null,
    checks,
    caveatList.firstChild ? el('div.info-box', { style: 'margin-top:14px' }, caveatList) : null);
}

// ---------------------------------------------------- who is covering what I own (SPEC 49.14)

/**
 * Coverage of a holding, from the `analyst*` fields HoldingsViewDecorator attaches to every
 * holdings read path. Nothing is decided here; the counting happens once, in Java.
 *
 * Three states have to stay visibly distinct, and they are the same three that every other lens
 * in this app keeps apart (Gotcha 21, 44, 121):
 *
 *   - the lookup did not run           -> unmeasured marker. Never "nobody covers it".
 *   - the ledger was searched, nothing -> a counted zero, with what that actually means.
 *   - N firms are quoting a target     -> the count, and the firms by name.
 *
 * The second is the one that needs care. This app sees a brokerage note only when it reaches one
 * of the feeds it reads (SPEC 49.7), so "no target on file" is a fact about the feed and not
 * about whether the company is covered. Every place a zero is drawn says so.
 */

/** Most-covered first; nothing-on-file last but ahead of never-looked. */
export function analystCoverageRank(row) {
  if (!row || row.analystHouses === null || row.analystHouses === undefined) return -1;
  return row.analystHouses;
}

/** What a zero means, said the same way everywhere it is drawn. */
const NONE_ON_FILE = 'No brokerage target has reached this app’s feeds for this stock. That is a '
  + 'statement about the feed, not about whether analysts cover the company — the ledger sees a '
  + 'note only if it is published where this app reads.';

function houseList(names, max = 8) {
  const all = Array.isArray(names) ? names : [];
  if (!all.length) return '';
  return all.length <= max ? all.join(', ')
    : all.slice(0, max).join(', ') + ' and ' + (all.length - max) + ' more';
}

/**
 * One table cell: how many firms, and who — the firms in the tooltip so the column stays narrow
 * (SPEC 27.10). The names in full are in the section below the table, which is where a reader
 * who actually wants to know "which analysts" will look.
 */
export function analystCoverageCell(row) {
  const n = row && row.analystHouses;

  if (n === null || n === undefined) {
    return unmeasured('The analyst ledger was not read for this holding. This is not a statement '
      + 'that nobody covers it.');
  }

  if (n === 0) {
    const ever = (row && row.analystHousesEver) || 0;
    if (ever > 0) {
      const node = badge('QUIET', { type: 'neutral', label: 'None running' });
      node.title = ever + ' firm' + (ever === 1 ? ' has' : 's have') + ' quoted a target on this '
        + 'stock before — ' + houseList(row.analystHouseNamesEver) + ' — but none is still '
        + 'running. Covered but quiet is a different thing from never covered.';
      node.setAttribute('data-no-gloss', '');
      return node;
    }
    const node = badge('NONE', { type: 'neutral', label: 'None on file' });
    node.title = NONE_ON_FILE;
    node.setAttribute('data-no-gloss', '');
    return node;
  }

  const node = el('span.badge.info', {}, num(n) + (n === 1 ? ' firm' : ' firms'));
  node.title = houseList(row.analystHouseNames)
    + (row.analystOpenTargets > n
      ? ' (' + num(row.analystOpenTargets) + ' live targets — a firm has revised)' : '')
    + (row.analystTargetsFrom && row.analystTargetsFrom !== row.symbol
      ? '. Filed under ' + displaySymbol(row.analystTargetsFrom) + '.' : '');
  node.setAttribute('data-no-gloss', '');

  // The level first, then what it implies. A percentage on its own cannot be checked against a
  // broker's note or a chart; the rupee figure is the thing a house actually published and the
  // move is derived from it. Showing only the derived number is the weaker half of the pair.
  if (missing(row.analystMedianTarget)) return node;

  let text = inr(row.analystMedianTarget, { abbreviate: false });
  if (!missing(row.analystUpsidePct)) {
    // Whole percent here on purpose: this sits under a badge in a 20-column table and the extra
    // digit buys nothing (SPEC 27.10). The panel below the table carries the precise figure.
    text += ' · ' + pct(row.analystUpsidePct, { digits: 0 });
  }

  const line = el('span.faint', { style: 'font-size:11.5px' }, text);
  line.title = targetBasis(row);

  return el('div', { style: 'display:flex;flex-direction:column;gap:2px' }, node, line);
}

/**
 * What the percentage is measured against, said out loud.
 *
 * The upside is computed from the price the ledger last stored for this stock, which comes from
 * the 13:20 measurement pass — NOT from the `currentPrice` on the holdings row beside it, which
 * comes from the broker sync. The two can differ by a day's move, and a reader who recomputes
 * the percentage against the Price column and gets a different answer will conclude the app is
 * wrong rather than that it is measuring from a different close. Naming the basis costs one
 * sentence; leaving it unnamed costs the reader's trust in the column.
 */
function targetBasis(row) {
  const n = row.analystHouses;
  const head = 'Median of ' + num(row.analystOpenTargets) + ' live target'
    + (row.analystOpenTargets === 1 ? '' : 's') + ' from ' + num(n) + ' firm'
    + (n === 1 ? '' : 's') + ': ' + houseList(row.analystHouseNames) + '.';

  const range = (!missing(row.analystLowestTarget) && !missing(row.analystHighestTarget)
    && row.analystLowestTarget !== row.analystHighestTarget)
    ? ' They range from ' + inr(row.analystLowestTarget, { abbreviate: false }) + ' to '
      + inr(row.analystHighestTarget, { abbreviate: false }) + '.' : '';

  const basis = missing(row.analystUpsidePct) ? ''
    : ' The move is measured against ' + inr(row.analystPriceAsStored, { abbreviate: false })
      + (row.analystPriceAsOf ? ', the close this ledger last stored on '
        + shortDate(row.analystPriceAsOf) : '')
      + ' — not the price in the column beside it, which is from a different pass.';

  return head + range + basis
    + ' A median over one or two calls is not a consensus, and this app endorses none of it.';
}

/**
 * The column, declared once.
 *
 * The header says "Analysts" on every screen that draws it, for the reason a column reading
 * "Brokers" here and "Coverage" there invites the reader to ask whether they are the same
 * measurement (Gotcha 85 applied to a heading).
 */
export function analystCoverageCol() {
  return {
    key: 'analystHouses',
    label: 'Analysts',
    value: analystCoverageRank,
    render: analystCoverageCell,
  };
}

/**
 * The chip group. "None on file" gets its own chip rather than being folded in with
 * "not measured" — a reader has to be able to ask for either deliberately (Gotcha 117 rule b).
 */
export function analystFilterGroup() {
  return {
    label: 'Analysts:',
    key: 'analyst',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'COVERED',
        text: 'Covered',
        test: (r) => (r.analystHouses || 0) > 0,
        title: 'At least one brokerage has a target still running on this stock.',
      },
      {
        value: 'AGREED',
        text: '3+ firms',
        test: (r) => (r.analystHouses || 0) >= 3,
        title: 'Three or more separate firms are quoting a target. Measured on the cross-section, '
          + 'most covered stocks carry one or two — three is where agreement starts to mean '
          + 'something (SPEC 49.12).',
      },
      {
        value: 'NONE',
        text: 'Nobody quoting',
        test: (r) => r.analystHouses === 0,
        title: 'The ledger was searched and no target is running. That is a fact about what '
          + 'reaches this app’s feeds, not about whether the company is covered.',
      },
    ],
  };
}

/**
 * The coverage line that must sit under any table drawing the Analysts column.
 *
 * Without it a column full of "None on file" reads as "the market has no view on what I own",
 * when what it actually means is that this app's ledger is thin by construction (SPEC 49.7).
 * Same rule as the macro and forensic coverage lines (Gotcha 44).
 */
export function analystCoverageLine(rows) {
  const all = rows || [];
  if (!all.length) return null;
  const looked = all.filter((r) => r.analystHouses !== null && r.analystHouses !== undefined);
  const covered = looked.filter((r) => r.analystHouses > 0);
  const quiet = looked.filter((r) => r.analystHouses === 0 && (r.analystHousesEver || 0) > 0);
  const never = looked.filter((r) => r.analystHouses === 0 && !(r.analystHousesEver || 0));

  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    covered.length + ' of ' + all.length + ' holdings have a brokerage target still running; '
    + quiet.length + ' have been covered before but have nothing live; '
    + never.length + ' have no target on file at all. '
    + 'That last group is not a finding about those companies: this app records a target only '
    + 'when a note reaches the feeds it reads (SPEC 49.7), so the ledger is thin by construction. '
    + 'None of this changes any score, and a brokerage target is not this app’s opinion.');
}

/**
 * "Who is covering what you own" — the full answer, names included.
 *
 * The column above gives a count and hides the names in a tooltip to keep the table narrow. A
 * reader who wants to know *which* firms is not going to hover thirty rows, so the names are
 * spelled out here, with the spread of what they are quoting beside them. Sorted by how many
 * firms, because that is the question being asked.
 */
export function analystCoveragePanel(rows) {
  const all = (rows || []).filter((r) => r.analystHouses !== null && r.analystHouses !== undefined);
  if (!all.length) return null;

  const covered = all.filter((r) => r.analystHouses > 0)
    .sort((a, b) => b.analystHouses - a.analystHouses);
  // The two zero states are NOT one group. A stock the desks have stopped quoting has been
  // covered; a stock with nothing on file has not, as far as this app can see. Folding them
  // together makes the second sentence below false about the first group, which is the whole
  // distinction this feature turns on (Gotcha 121). Found by rendering it, not in review.
  const quiet = all.filter((r) => !r.analystHouses && (r.analystHousesEver || 0) > 0);
  const never = all.filter((r) => !r.analystHouses && !(r.analystHousesEver || 0));

  const houses = new Set();
  for (const r of covered) for (const h of (r.analystHouseNames || [])) houses.add(h);

  const head = el('div.grid.kpis', {},
    kpi({
      label: 'Holdings with a live target',
      value: num(covered.length) + ' of ' + num(all.length),
      raw: covered.length,
      tone: 'neutral',
      sub: 'brokerage targets still running',
    }),
    kpi({
      label: 'Firms covering your book',
      value: num(houses.size),
      raw: houses.size,
      tone: 'neutral',
      sub: houses.size ? 'distinct brokerages' : 'none on file',
    }),
    kpi({
      label: 'No live target',
      value: num(quiet.length + never.length),
      raw: quiet.length + never.length,
      tone: 'neutral',
      sub: never.length + ' never quoted, ' + quiet.length + ' covered before',
    }));

  const tbl = covered.length ? table([
    {
      key: 'symbol',
      label: 'Stock',
      render: (r) => el('a', { href: stockHref(r.symbol), title: r.symbol }, displaySymbol(r.symbol)),
    },
    {
      key: 'analystHouses',
      label: 'Firms',
      align: 'r',
      render: (r) => el('span.num', {}, num(r.analystHouses)),
    },
    {
      key: 'analystHouseNames',
      label: 'Who',
      sortable: false,
      render: (r) => el('span', {}, houseList(r.analystHouseNames, 12)),
    },
    {
      key: 'analystOpenTargets',
      label: 'Targets',
      align: 'r',
      render: (r) => {
        const node = el('span.num', {}, num(r.analystOpenTargets));
        if (r.analystOpenTargets > r.analystHouses) {
          node.title = 'More targets than firms: a house has published more than one live call. '
            + 'The firm count is the number of opinions (B-041).';
        }
        return node;
      },
    },
    {
      key: 'analystMedianTarget',
      label: 'Median target',
      align: 'r',
      render: (r) => (missing(r.analystMedianTarget) ? unmeasured('No figure quoted')
        : el('span', { title: 'Median of the live targets. Never a consensus — on most stocks '
            + 'this is one or two calls.' }, inr(r.analystMedianTarget, { abbreviate: false }))),
    },
    {
      key: 'analystSpread',
      label: 'Range',
      sortable: false,
      render: (r) => (missing(r.analystLowestTarget) || missing(r.analystHighestTarget)
        ? el('span.muted', {}, '—')
        : el('span.muted', { style: 'font-size:12px' },
          inr(r.analystLowestTarget, { abbreviate: false }) + ' – '
          + inr(r.analystHighestTarget, { abbreviate: false }))),
    },
    {
      key: 'analystUpsidePct',
      label: 'vs last price',
      align: 'r',
      render: (r) => {
        if (missing(r.analystUpsidePct)) {
          return unmeasured('The ledger has no stored price for this stock yet');
        }
        const node = el('span.num' + (r.analystUpsidePct > 0 ? '.positive' : '.negative'), {},
          pct(r.analystUpsidePct));
        // Same obligation as the cell above: name the price this is measured from, or the reader
        // recomputes it against a different one and concludes the app cannot do arithmetic.
        node.title = 'From ' + inr(r.analystPriceAsStored, { abbreviate: false })
          + (r.analystPriceAsOf ? ', the close this ledger last stored on '
            + shortDate(r.analystPriceAsOf) : '')
          + ', to the median target. Not from the live price.';
        return node;
      },
    },
    {
      key: 'analystLastCallOn',
      label: 'Last call',
      render: (r) => (r.analystLastCallOn ? shortDate(r.analystLastCallOn)
        : unmeasured('No date recorded')),
    },
  ], covered, { sortKey: 'analystHouses', filter: false }) : null;

  const none = el('div', {});
  if (quiet.length) {
    none.append(el('div', { style: 'margin-top:12px;font-size:13px' },
      el('strong', {}, 'Covered before, nothing running now: '),
      quiet.map((r) => displaySymbol(r.symbol)).join(', '),
      '. A brokerage has quoted a target on each of these at some point, but every one has run '
      + 'its course or been revised away. Desks going quiet on a stock you own is worth noticing; '
      + 'it is a different thing from never having been covered.'));
  }
  if (never.length) {
    none.append(el('div', { style: 'margin-top:12px;font-size:13px' },
      el('strong', {}, 'No target on file: '),
      never.map((r) => displaySymbol(r.symbol)).join(', '),
      '. ', NONE_ON_FILE));
  }

  return el('div', {}, head, tbl, none.firstChild ? none : null,
    el('div.info-box', { style: 'margin-top:14px' },
      el('ul', {},
        el('li', {}, 'A brokerage target is somebody else’s opinion, recorded so it can be '
          + 'scored later. It is not this app’s view and it changes no score here.'),
        el('li', {}, 'The firm count is the number of opinions, not the number of notes — a house '
          + 'that revised its target three times is still one firm.'),
        el('li', {}, 'The median is a median of however many calls exist, which on most stocks is '
          + 'one or two. That is not a consensus and the firm count is printed beside it so it '
          + 'cannot be read as one.'),
        el('li', {}, 'How each of these firms has actually done is on the Accuracy page — and '
          + 'the sell-side sample here is roughly four-fifths Buy, so read a hit rate against '
          + 'the market, not on its own.'))));
}
