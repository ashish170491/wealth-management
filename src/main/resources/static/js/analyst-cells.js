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
import { el, badge, unmeasured, kpi } from './ui.js';
import { inr, pct, num, shortDate, missing, NOT_MEASURED, humanLabel } from './format.js';

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
