/**
 * Shared renderers for the mutual-fund vocabulary (SPEC 52.8).
 *
 * Extracted on the day the Funds page shipped, before there was a second reader, because the
 * lesson that produced `compounding.js`, `macro-cells.js`, `earnings-cells.js` and
 * `analyst-cells.js` cost a real defect each time it was learned late: a shared payload needs a
 * shared renderer, or the second screen to draw it draws it differently (B-099). The fund lens is
 * already designed to be readable from a portfolio column one day; when that happens it imports
 * this and inherits every rule below for free.
 *
 * Three rules are encoded here rather than left to each caller.
 *
 * NOT_MEASURED AND NOT_COMPARABLE ARE DRAWN AS TWO DIFFERENT THINGS (Gotcha 121). "Not measured"
 * means this app has too little history - its own gap, and it gets the striped marker that means
 * exactly that everywhere else in the dashboard. "Not comparable" means the fund WAS measured
 * perfectly well and there is nothing like-for-like to grade it against: an income-distribution
 * series whose NAV drops by every payout, or a category with four schemes in it. That is a
 * finding about the question, not a hole in the data, and collapsing the two would let a refusal
 * read as a failure to look.
 *
 * NOTHING IS COLOURED AS AN INSTRUCTION. A fund behind its category over rolling windows is
 * amber, never red. Red on this screen would be read as "sell", and the strength of the evidence
 * does not support that reading from two independent windows - the count of overlapping windows
 * is not a sample size (SPEC 52.8, Gotcha 143).
 *
 * THE REASON TRAVELS WITH THE BADGE. Every verdict the server sends carries the sentence it was
 * reached on, and every badge here puts it in the tooltip. A reader who can see why can disagree.
 */

import { el, badge, unmeasured } from './ui.js';
import { humanLabel, pct, pp, num, missing } from './format.js';

/**
 * Badge class per verdict, local rather than routed through `badgeType()`.
 *
 * Same reasoning as `macro-cells.js`: the shared table maps words like MIXED and NEUTRAL by their
 * meaning elsewhere in the app, and a fund verdict that happens to share a token would inherit a
 * colour chosen for a different question.
 */
const TONE = {
  // The record over rolling windows.
  AHEAD_OF_BENCHMARK: 'success',
  MIXED_RECORD: 'neutral',
  BEHIND_BENCHMARK: 'warning',
  // Where it sat inside its own category.
  UPPER_QUARTILE: 'success',
  UPPER_HALF: 'info',
  LOWER_HALF: 'neutral',
  LOWER_QUARTILE: 'warning',
  TOO_FEW_PEERS: 'neutral',
  // What the plan costs.
  DIRECT_PLAN: 'success',
  REGULAR_PLAN_MEASURED: 'warning',
  REGULAR_PLAN_NO_TWIN: 'neutral',
  TER_NOT_MEASURED: 'neutral',
  NOT_APPLICABLE: 'neutral',
  // Whether an index exists to compare against.
  HELD_EXACT: 'success',
  HELD_PROXY: 'info',
  HELD_BLEND: 'info',
  NOT_HELD: 'neutral',
  NOT_SINGULAR: 'neutral',
  OUT_OF_SCOPE: 'neutral',
  NOT_COMPARABLE: 'neutral',
  // Whether picking on past record worked. Deliberately unopinionated colours: this is a finding
  // about a METHOD, not about a fund, and "no better than chance" is not bad news - it is the
  // answer. Painting it red would make an honest measurement look like a failure.
  PERSISTENCE_FOUND: 'info',
  WEAK_PERSISTENCE: 'info',
  NO_BETTER_THAN_CHANCE: 'neutral',
  REVERSAL: 'neutral',
};

/** Values that mean "this app could not look", and therefore get the striped marker. */
const STRIPED = new Set(['NOT_MEASURED']);

/**
 * One verdict, as a badge carrying its own reason.
 *
 * @param value  the enum name from the server
 * @param label  the server's own plain-English label, preferred over any local guess
 * @param reason the sentence the verdict was reached on - becomes the tooltip
 */
export function verdictBadge(value, label, reason) {
  if (value === null || value === undefined || value === '') {
    return unmeasured(reason || 'No reading on file.');
  }
  const key = String(value).toUpperCase();
  if (STRIPED.has(key)) return unmeasured(reason || 'Not enough history to measure this yet.');
  const node = badge(key, { type: TONE[key] || 'info', label: label || humanLabel(key) });
  if (reason) node.title = reason;
  // These words are the app's own vocabulary, not market usage, and the glossary walker would
  // otherwise try to gloss fragments of them mid-badge.
  node.setAttribute('data-no-gloss', '');
  return node;
}

/**
 * The record over rolling windows, with the counts that keep it honest underneath.
 *
 * `windowsMeasured` is always printed beside `independentWindows` and never alone. Overlapping
 * monthly windows hold no more information than the months underneath them - roughly 100 months
 * of history gives ~60 three-year windows and TWO independent ones - so reading the window count
 * as a sample size is Gotcha 92 in a new asset class. No significance language appears anywhere,
 * and the word "consistently" is withheld below two independent windows by the server.
 */
export function recordCell(h) {
  const badgeNode = verdictBadge(h.recordVerdict, h.recordVerdictLabel, h.recordReason);
  if (!h.windowsMeasured) return badgeNode;
  const share = missing(h.shareAheadPct) ? null : num(h.shareAheadPct, 0) + '% of windows';
  const excess = missing(h.medianExcessPp) ? null : 'median ' + pp(h.medianExcessPp);
  return el('div', {},
    badgeNode,
    el('div.faint', { style: 'font-size:12px;margin-top:3px' },
      'ahead in ' + num(h.windowsAhead, 0) + ' of ' + num(h.windowsMeasured, 0)
      + ' rolling ' + num(h.windowMonths, 0) + '-month windows'
      + (share ? ' (' + share + ')' : '')
      + (excess ? ', ' + excess : '')),
    el('div.faint', { style: 'font-size:12px' },
      num(h.independentWindows, 0) + ' independent '
      + (h.independentWindows === 1 ? 'window' : 'windows')
      + ' - that is the figure that behaves like a sample size, and it is small'));
}

/**
 * 1st / 2nd / 3rd / 4th, with the teens handled.
 *
 * Trivial, and it is here because the first render printed "73th percentile" on the one fund the
 * app had measured - the single most carefully-reasoned number on the screen, wearing a typo.
 */
function ordinal(n) {
  const v = Math.round(n);
  const tens = v % 100;
  if (tens >= 11 && tens <= 13) return v + 'th';
  return v + ({ 1: 'st', 2: 'nd', 3: 'rd' }[v % 10] || 'th');
}

/** Where the fund sat inside its own SEBI category, with the peer count that bounds the claim. */
export function standingCell(h) {
  const badgeNode = verdictBadge(h.categoryStanding, h.categoryStandingLabel, h.categoryStandingReason);
  const bits = [];
  if (!missing(h.categoryPercentile)) bits.push(ordinal(h.categoryPercentile) + ' percentile');
  if (!missing(h.ownReturnPct) && !missing(h.categoryMedianPct)) {
    bits.push(pct(h.ownReturnPct, { signed: false }) + ' a year against a category median of '
      + pct(h.categoryMedianPct, { signed: false }));
  }
  if (h.categoryPeers) bits.push(num(h.categoryPeers, 0) + ' peer schemes');
  if (!bits.length) return badgeNode;
  return el('div', {}, badgeNode,
    el('div.faint', { style: 'font-size:12px;margin-top:3px' }, bits.join(' · ')));
}

/**
 * What the plan costs - the one figure on this screen that does not decay.
 *
 * A fund's past return does not predict its future return; the commission built into a Regular
 * plan is charged every year regardless. The caveat is mandatory whenever a drag is named,
 * because stating the saving without stating that moving plans is a redemption plus a purchase
 * - taxable, possibly exit-loaded - reads as advice rather than as a fact (SPEC 52.8).
 */
export function planCostCell(h) {
  const badgeNode = verdictBadge(h.planCostVerdict, h.planCostVerdictLabel, h.planCostReason);
  if (missing(h.planAnnualDragPct)) return badgeNode;
  return el('div', {}, badgeNode,
    el('div.faint', { style: 'font-size:12px;margin-top:3px' },
      'the Direct plan of the same portfolio compounded ' + pp(h.planAnnualDragPct)
      + ' a year faster'));
}

/** Scheme name over its AMC and category - one cell, so tables stay inside the column budget. */
export function schemeCell(h) {
  return el('div', {},
    el('div', {}, h.schemeName || h.fundName || '—'),
    el('div.faint', { style: 'font-size:12px' },
      [h.amcName, h.categoryLabel || humanLabel(h.category)].filter(Boolean).join(' · ')));
}

/**
 * Plan and option, as a plain badge pair.
 *
 * The option is load-bearing rather than decorative: under Income Distribution the fund pays
 * money out and its NAV falls by the amount paid, so an IDCW series records the return after
 * every distribution while its Growth peers record it before. That is why an IDCW holding reads
 * "not comparable" above rather than reading badly (B-131).
 */
export function planCell(h) {
  const nodes = [];
  if (h.plan) {
    nodes.push(badge(h.plan, {
      type: h.plan === 'DIRECT' ? 'success' : 'neutral',
      label: h.plan === 'DIRECT' ? 'Direct' : h.plan === 'REGULAR' ? 'Regular' : humanLabel(h.plan),
    }));
  }
  if (h.option) {
    const isGrowth = h.option === 'GROWTH';
    const b = badge(h.option, {
      type: 'neutral',
      label: isGrowth ? 'Growth' : h.option === 'IDCW' ? 'IDCW (pays out)' : humanLabel(h.option),
    });
    b.title = isGrowth
      ? 'Growth: nothing is paid out, so the NAV carries the whole return and is comparable with '
        + 'the rest of its category.'
      : 'Income Distribution cum Capital Withdrawal: the fund pays money out and its NAV drops by '
        + 'the amount paid, so its published return is after distributions and cannot be set '
        + 'against peers measured before them.';
    b.setAttribute('data-no-gloss', '');
    nodes.push(b);
  }
  if (!nodes.length) {
    return unmeasured('The scheme master carries no plan or option for this fund.');
  }
  return el('div', { style: 'display:flex;gap:4px;flex-wrap:wrap' }, ...nodes);
}
