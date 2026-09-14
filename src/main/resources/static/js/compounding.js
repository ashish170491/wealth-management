/**
 * "Can this business compound?" — the long-horizon quality lens (SPEC 41).
 *
 * The verdict is computed server-side by CompoundingQuality.java and arrives on each screening
 * row as `compounding` plus `compoundingGates`. Nothing is decided here: this file only chooses a
 * wording and a colour, so the rule table stays in one testable place — the same split as
 * buy-timing.js.
 *
 * Two things this must never do. It must not present itself as a score: the badge is a gate, and
 * the composite beside it is a different question (that one is 59% price behaviour, which is why
 * this exists). And NOT_MEASURED must render as the striped "not measured" marker rather than as
 * a low grade — a business whose filings we could not read is not a business that failed
 * (Gotcha 21, 44).
 */
import { el, badge, unmeasured } from './ui.js';

/** Verdict -> how it reads and how it looks. Plain words, no jargon (SPEC 21). */
export const COMPOUNDING = {
  COMPOUNDER: { type: 'success', label: 'Compounder' },
  PARTIAL: { type: 'info', label: 'Partly' },
  NO: { type: 'warning', label: 'No' },
};

/** Sort order: compounders first, then partial, then no, with unmeasured always last. */
export function compoundingRank(row) {
  const v = row && row.compounding;
  if (v === 'COMPOUNDER') return 0;
  if (v === 'PARTIAL') return 1;
  if (v === 'NO') return 2;
  return 3;
}

/**
 * One table cell. The reason and the passed/applicable count go in the tooltip so the column
 * stays narrow — the full checklist is on the stock page.
 */
export function compoundingCell(row) {
  const v = row && row.compounding;
  const reason = (row && row.compoundingReason) || '';

  if (!v || v === 'NOT_MEASURED') {
    return unmeasured(reason || 'Not enough of the company accounts could be read to judge this');
  }
  const spec = COMPOUNDING[v];
  if (!spec) return unmeasured(reason || 'Unrecognised verdict');

  const passed = row.compoundingPassed;
  const applicable = row.compoundingApplicable;
  // Deliberately terse. This is one of 23 columns on a table that must not scroll sideways
  // (SPEC 27.10) and the header sets the floor, so under "Compounds?" the word "Compounder"
  // adds width without adding meaning that "Yes" does not carry. The full wording, the reason
  // and the track-record caveat are all in the tooltip, and the checklist is on the stock page.
  const label = v === 'COMPOUNDER' ? 'Yes'
    : v === 'PARTIAL' ? `${passed}/${applicable}` : 'No';

  const node = badge(v, { type: spec.type, label });
  node.title = reason + yearsNote(row.compoundingYearsOfAccounts);
  return node;
}

/**
 * The sentence that stops the badge being over-read. Every check is evaluated on the LATEST year
 * of accounts; how many years exist is a separate fact, and for most of the universe it is one.
 * Saying so is the difference between "this business looks like a compounder on current numbers"
 * and "this business has compounded", which are not the same claim.
 */
function yearsNote(years) {
  if (years === null || years === undefined) {
    return ' Based on the latest year of accounts; how many years are on file is not known.';
  }
  if (years >= 5) {
    return ` Checks use the latest year; ${years} years of accounts are on file, `
      + 'so the record can be verified.';
  }
  if (years <= 1) {
    return ' Based on ONE year of accounts — current quality, not yet a track record.';
  }
  return ` Based on the latest year; only ${years} years are on file, so this is not yet a track record.`;
}

/** Verdict -> the headline sentence on the stock page. */
function headline(v) {
  switch (v) {
    case 'COMPOUNDER':
      return 'On its current numbers this business has what compounding needs.';
    case 'PARTIAL':
      return 'Some of what compounding needs, but not all of it.';
    case 'NO':
      return 'The numbers do not support a long compounding runway today.';
    default:
      return 'Not enough of the accounts could be read to answer this.';
  }
}

const GATE_ICON = {
  PASS: { mark: '✓', cls: 'positive', title: 'Passed' },
  FAIL: { mark: '✕', cls: 'negative', title: 'Failed' },
  NOT_APPLICABLE: { mark: '—', cls: 'faint', title: 'Does not apply to this kind of business' },
  NOT_MEASURED: { mark: '?', cls: 'faint', title: 'Could not be measured' },
};

/**
 * The full checklist for the stock page.
 *
 * Every gate is listed, including the ones that could not be measured or do not apply — a
 * checklist that quietly dropped them would let a business look thoroughly examined when it was
 * barely examined at all (Gotcha 44, 68).
 */
export function compoundingPanel(row) {
  if (!row) return null;
  const v = row.compounding;
  const gates = Array.isArray(row.compoundingGates) ? row.compoundingGates : [];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    v && v !== 'NOT_MEASURED' && COMPOUNDING[v]
      ? badge(v, COMPOUNDING[v])
      : unmeasured('Not enough of the accounts could be read'),
    el('span', { style: 'font-weight:600;font-size:14px' }, headline(v)));

  const list = el('div', {});
  for (const g of gates) {
    const icon = GATE_ICON[g.status] || GATE_ICON.NOT_MEASURED;
    list.append(el('div.row', { style: 'gap:10px;align-items:baseline;margin-bottom:7px' },
      el('span.' + icon.cls, { title: icon.title, style: 'font-weight:700;min-width:14px' }, icon.mark),
      el('span', {
        style: 'min-width:230px;font-size:13.5px'
          + (g.status === 'PASS' || g.status === 'FAIL' ? '' : ';opacity:.65'),
      }, g.question),
      el('span.muted', { style: 'font-size:13px;flex:1;min-width:200px' }, g.detail || '')));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });
  if (row.compoundingReason) {
    foot.append(el('div', { style: 'margin-bottom:6px' }, row.compoundingReason));
  }
  foot.append(el('div.muted', {}, yearsNote(row.compoundingYearsOfAccounts).trim()));
  if (row.compoundingCapexContext) {
    foot.append(el('div.muted', { style: 'margin-top:5px' },
      'Reinvestment: ' + row.compoundingCapexContext
      + '. Shown for context — it is not one of the checks, because how much profit is kept '
      + 'rather than paid out is not measured yet.'));
  }
  foot.append(el('div.muted', { style: 'margin-top:5px' },
    'This does not change the score above it. It is a separate read on the business, '
    + 'kept out of the score until it has been measured against real returns.'));

  return el('div', {}, head, list, foot);
}
