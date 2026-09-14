/**
 * "Still good time to buy?" cell, shared by the screener and discovery tables (SPEC 12.11).
 *
 * The verdict itself is computed server-side by ScreenerTimingVerdict.java and arrives on each
 * screening row as `buyTiming` / `buyTimingReason`. Nothing is decided here — this file only
 * chooses a colour and a wording, so the rule table stays in one testable place.
 *
 * NOT_MEASURED renders as the striped "not measured" marker, never as a neutral verdict: a stock
 * we could not judge must not look like one we judged and found unremarkable (Gotcha 21).
 */
import { el, badge, unmeasured } from './ui.js';
import { inrExact } from './format.js';

/** Verdict -> how it reads and how it looks. Wording is deliberately plain (SPEC 21). */
export const BUY_TIMING = {
  BUY_NOW: { type: 'success', label: 'Good entry' },
  ACCUMULATE: { type: 'info', label: 'Buy in tranches' },
  WAIT_FOR_PULLBACK: { type: 'warning', label: 'Wait — running hot' },
  HOLD_OFF: { type: 'warning', label: 'Hold off' },
  AVOID: { type: 'danger', label: 'Avoid' },
};

/**
 * One table cell. The reason is the tooltip rather than inline text so the column stays narrow;
 * the full sentence is also what the stock page shows.
 */
export function buyTimingCell(row) {
  const v = row && row.buyTiming;
  const reason = (row && row.buyTimingReason) || '';

  if (!v || v === 'NOT_MEASURED') {
    return unmeasured(reason || 'Not enough data from the last screening to judge the entry');
  }

  const spec = BUY_TIMING[v];
  if (!spec) return unmeasured(reason || 'Unrecognised verdict');

  const node = badge(v, spec);
  // A tracked stock shows the watchlist's verdict, computed from live daily prices rather than
  // the last screening's weekly figures. Saying so in the tooltip is what keeps the two screens
  // from looking like they disagree when one is simply better informed.
  const src = row && row.buyTimingSource === 'WATCHLIST'
    ? ' (from your watchlist — based on the latest price)'
    : '';
  // Where the price sits in its 52-week range is the other half of "N% below the high": a stock
  // 13% off its high can still be 53% above its low (B-062). Carried on screener rows only.
  const pos = row && Number.isFinite(row.rangePosition52w)
    ? ` Sits ${Math.round(row.rangePosition52w)}% of the way up its 52-week range (0 = at the low, 100 = at the high).`
    : '';
  if (reason || pos) node.title = (reason || '') + src + pos;
  return node;
}

/** Sort key: best entry first, with unmeasured always last regardless of direction. */
export function buyTimingRank(row) {
  const order = { BUY_NOW: 0, ACCUMULATE: 1, WAIT_FOR_PULLBACK: 2, HOLD_OFF: 3, AVOID: 4 };
  const v = row && row.buyTiming;
  return v in order ? order[v] : 99;
}

/**
 * The entry ladder (SPEC 12.12).
 *
 * Three equal tranches, computed server-side and conditioned on the verdict, so this cell and the
 * verdict cell beside it cannot disagree - a waiting verdict's rungs all sit below today's price
 * by construction (B-068). The cell shows the levels; the tooltip carries the plain-English plan
 * and, for a waiting plan, the fallback - what to do if the dip never comes, which is the sentence
 * an amateur never writes down.
 *
 * No plan is the striped marker with the server's reason: an AVOID row deliberately has none, and
 * that is a finding, not a gap.
 */
export function entryPriceCell(row) {
  const rungs = (row && row.suggestedEntryRungs) || [];
  const reason = (row && row.suggestedEntryReason) || '';
  if (!rungs.length) return unmeasured(reason || 'No entry plan suggested');

  const node = el('div');
  for (const r of rungs) {
    const line = el('div', { style: 'white-space:nowrap' });
    line.appendChild(el('span.muted', { style: 'font-size:10.5px' }, r.sharePercent + '% '));
    line.appendChild(el('span', {}, r.label === 'now' ? 'now' : inrExact(r.price, true)));
    node.appendChild(line);
  }
  const basis = row.suggestedEntryBasis;
  if (basis) {
    node.appendChild(el('span.muted', { style: 'display:block;font-size:10.5px' }, basis));
  }
  const gap = String.fromCharCode(10, 10);
  node.title = reason + (row.suggestedEntryFallback ? gap + row.suggestedEntryFallback : '');
  return node;
}
