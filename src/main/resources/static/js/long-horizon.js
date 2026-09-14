/**
 * "Has it held?" and "what did management do with the cash?" — the two multi-year reads
 * (SPEC 42, SPEC 43).
 *
 * Both verdicts are computed server-side by CompoundingPersistence.java and
 * CapitalAllocationRecord.java and arrive together from GET /api/fundamentals/long-horizon.
 * Nothing is decided here: this file picks wording and colour only, the same split as
 * compounding.js and buy-timing.js, so the rules stay in one testable place.
 *
 * Why this sits beside the five-gate compounding panel rather than replacing it. That panel
 * answers "can this business compound?" from the LATEST YEAR. This one answers "has it?" across
 * as many years as we hold. They are different claims and a stock with one good year and no
 * track record has to be able to read as exactly that — so the two are shown side by side and
 * never merged.
 *
 * Three things this must never do:
 *  - Render NOT_MEASURED as a low grade. Most of the universe has one year of accounts on file,
 *    so "we could not check" is the common case, not the exception (Gotcha 21, 44).
 *  - Drop a gate or component that could not be measured. A checklist that quietly hides them
 *    lets a business look thoroughly examined when it was barely examined at all (Gotcha 68).
 *  - Imply either reading changes the score. Neither contributes a single point.
 */
import { el, badge, unmeasured } from './ui.js';
import { pct, num } from './format.js';

/** Track-record verdict -> how it reads and how it looks. Plain words, no jargon (SPEC 21). */
export const TRACK_RECORD = {
  PROVEN_COMPOUNDER: { type: 'success', label: 'Held up' },
  PARTIAL: { type: 'info', label: 'Mixed' },
  NO: { type: 'warning', label: 'Did not hold' },
};

/** Capital-allocation verdict -> the same treatment. */
export const ALLOCATION = {
  DISCIPLINED: { type: 'success', label: 'Disciplined' },
  MIXED: { type: 'info', label: 'Mixed' },
  POOR: { type: 'warning', label: 'Poor' },
};

const GATE_ICON = {
  PASS: { mark: '✓', cls: 'positive', title: 'Held up' },
  FAIL: { mark: '✕', cls: 'negative', title: 'Did not hold' },
  NOT_APPLICABLE: { mark: '—', cls: 'faint', title: 'Does not apply to this kind of business' },
  NOT_MEASURED: { mark: '?', cls: 'faint', title: 'Could not be measured' },
};

const STATUS_ICON = {
  STRONG: { mark: '✓', cls: 'positive', title: 'Strong' },
  ADEQUATE: { mark: '~', cls: 'neutral', title: 'Adequate' },
  WEAK: { mark: '✕', cls: 'negative', title: 'Weak' },
  NOT_APPLICABLE: { mark: '—', cls: 'faint', title: 'Does not apply to this kind of business' },
  NOT_MEASURED: { mark: '?', cls: 'faint', title: 'Could not be measured' },
};

function headlineTrack(v) {
  switch (v) {
    case 'PROVEN_COMPOUNDER':
      return 'This business has behaved like a compounder over the years on file.';
    case 'PARTIAL':
      return 'A record with a real strength and a real weakness.';
    case 'NO':
      return 'The record does not show a business that compounded.';
    default:
      return 'Not enough years of accounts to judge a track record either way.';
  }
}

function headlineAllocation(v) {
  switch (v) {
    case 'DISCIPLINED':
      return 'Management has handled your money well.';
    case 'MIXED':
      return 'Some good decisions with your money, some poor ones.';
    case 'POOR':
      return 'Management has not handled your money well.';
    default:
      return 'Not enough of the accounts could be read to judge how the cash was handled.';
  }
}

/**
 * The line that stops either badge being over-read.
 *
 * How many years exist is a separate fact from what those years say, and for most of the
 * universe it is one. The universe backfill is filling this in over a few weeks, so a stock
 * showing "not enough years" today may well be answerable next month — which is worth saying,
 * because otherwise it reads as a permanent verdict on the company.
 */
function yearsLine(years) {
  if (years === null || years === undefined || years === 0) {
    return 'No annual accounts on file for this company yet. The app is working through the '
      + 'whole list a few stocks a day, so this should fill in over the coming weeks.';
  }
  if (years === 1) {
    return 'Only ONE year of accounts is on file, so there is no record to read yet — this is '
      + 'not a judgement on the company.';
  }
  if (years < 5) {
    return `Only ${years} years of accounts are on file. A track record needs at least five, `
      + 'so nothing is claimed either way yet.';
  }
  return `${years} years of accounts on file. Older filings often carry the profit figures `
    + 'without the balance sheet, so some checks below may still say they could not be measured.';
}

/** One checklist row, shared by both panels so they cannot drift apart visually. */
function row(icon, question, detail, trailing) {
  const dim = icon.cls === 'faint' ? ';opacity:.65' : '';
  return el('div.row', { style: 'gap:10px;align-items:baseline;margin-bottom:7px' },
    el('span.' + icon.cls, { title: icon.title, style: 'font-weight:700;min-width:14px' }, icon.mark),
    el('span', { style: 'min-width:250px;font-size:13.5px' + dim }, question),
    el('span.muted', { style: 'font-size:13px;flex:1;min-width:200px' }, detail || ''),
    trailing || el('span', {}));
}

/**
 * "Has it held?" — the multi-year track record (SPEC 43).
 *
 * Each gate shows the count of years it held in over the years it could be measured in, because
 * that ratio IS the finding. An average would let one boom year carry a decade, which is exactly
 * what a persistence check exists to catch, so the count is shown rather than a grade.
 */
export function trackRecordPanel(view, singleYear) {
  if (!view || !view.trackRecord) return null;
  const t = view.trackRecord;
  const v = t.verdict;
  const gates = Array.isArray(t.gates) ? t.gates : [];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    v && v !== 'NOT_MEASURED' && TRACK_RECORD[v]
      ? badge(v, TRACK_RECORD[v])
      : unmeasured('Not enough years of accounts to judge'),
    el('span', { style: 'font-weight:600;font-size:14px' }, headlineTrack(v)));

  const list = el('div', {});
  for (const g of gates) {
    const icon = GATE_ICON[g.status] || GATE_ICON.NOT_MEASURED;
    // Only show a year count where one exists. Two of the six gates are a single verdict over
    // the whole period rather than a per-year test, and printing "1/1" beside them would look
    // like a one-year sample when it is nothing of the kind.
    const showCount = g.measuredYears > 1;
    const trailing = showCount
      ? el('span', {
        title: `Held in ${g.qualifyingYears} of the ${g.measuredYears} years that could be measured`,
        style: 'font-size:13px;font-weight:600;min-width:52px;text-align:right',
      }, `${g.qualifyingYears} of ${g.measuredYears}`)
      : el('span', { style: 'min-width:52px' });
    list.append(row(icon, g.question, g.detail, trailing));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });
  const divergence = divergenceNote(t.verdict, singleYear && singleYear.compounding);
  if (divergence) {
    // Named rather than left to the reader. The two panels sit one above the other and their
    // headline words differ deliberately, but a badge reading "No" above a badge reading
    // "Held up" looks like the app arguing with itself unless the difference is explained —
    // and the difference is the most useful thing on the page (SPEC 21: the reader is not a
    // stock-market expert, so an unexplained divergence is a defect, not a nuance).
    list.append(el('div.row', {
      // Variable names verified against css/app.css. The first draft used --accent and
      // --surface-2, neither of which exists, so the callout rendered as plain text with no
      // border and no tint — present in the DOM, absent from the page. That is Gotcha 90's
      // failure mode exactly, and it was caught by looking at a screenshot rather than by
      // reading the attribute back.
      style: 'gap:10px;align-items:baseline;margin-top:12px;padding:10px 12px;'
        + 'border-left:3px solid var(--warn);background:var(--warn-bg);'
        + 'border-radius:var(--radius);font-size:13.5px',
    }, el('span', {}, divergence)));
  }
  if (t.reason) foot.append(el('div', { style: 'margin-bottom:6px' }, t.reason));
  foot.append(el('div.muted', {}, yearsLine(view.yearsOfAccounts)));
  if (view.resolvedSymbol && view.resolvedSymbol !== view.symbol) {
    // Say which listing answered. The accounts describe the company, not the exchange it trades
    // on, so reusing the NSE history for a BSE-held position is correct — but it should be
    // visible rather than silent.
    foot.append(el('div.muted', { style: 'margin-top:5px' },
      `Accounts read under ${view.resolvedSymbol}. Same company, different listing.`));
  }
  foot.append(el('div.muted', { style: 'margin-top:5px' },
    'This does not change the score above it, and it is not advice to buy or sell. '
    + 'It is a record of what already happened.'));
  return el('div', {}, head, list, foot);
}

/**
 * What it means when the record and the latest year disagree.
 *
 * This is not a conflict to be resolved — the two panels answer different questions, and the gap
 * between them carries information neither one carries alone. A business that compounded for
 * years and has just had a weak year is the thesis-drift case, which usually shows in the
 * accounts before it shows in the price. The reverse is the cyclical case: one good year at the
 * top of a cycle, which is exactly what a track record exists to see past.
 *
 * Returns null when they agree, because a note saying "these agree" is noise.
 */
function divergenceNote(record, latest) {
  const recordGood = record === 'PROVEN_COMPOUNDER';
  const recordBad = record === 'NO';
  const latestGood = latest === 'COMPOUNDER';
  const latestBad = latest === 'NO';

  if (recordGood && latestBad) {
    return 'Worth reading twice: this business compounded over the years on file, but its most '
      + 'recent year does not clear the same bar. That gap is what a weakening business looks '
      + 'like before the share price reacts. Compare the checks above with the five in the '
      + 'section before it and see which ones changed.';
  }
  if (recordBad && latestGood) {
    return 'Worth reading twice: the latest year looks strong but the longer record does not '
      + 'support it. One good year is what a cyclical business shows at the top of its cycle, '
      + 'which is the main thing a track record exists to see past.';
  }
  if (recordGood && latest === 'PARTIAL') {
    return 'The record is strong and the latest year is mixed. Not a warning on its own, but '
      + 'worth knowing which check softened.';
  }
  return null;
}

/**
 * "What did management do with your money?" — the capital-allocation record (SPEC 42).
 *
 * The half of the judgement the app never had. Profits retained and reinvested badly compound
 * nothing, and a share count growing as fast as profits hands the owner nothing either.
 */
export function capitalAllocationPanel(view) {
  if (!view || !view.capitalAllocation) return null;
  const a = view.capitalAllocation;
  const v = a.verdict;
  const components = Array.isArray(a.components) ? a.components : [];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    v && v !== 'NOT_MEASURED' && ALLOCATION[v]
      ? badge(v, ALLOCATION[v])
      : unmeasured('Not enough of the accounts could be read'),
    el('span', { style: 'font-weight:600;font-size:14px' }, headlineAllocation(v)));

  const list = el('div', {});
  for (const c of components) {
    const icon = STATUS_ICON[c.status] || STATUS_ICON.NOT_MEASURED;
    const trailing = el('span', {
      style: 'font-size:13px;font-weight:600;min-width:62px;text-align:right',
    }, figureFor(c));
    list.append(row(icon, c.question, c.detail, trailing));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });
  if (a.reason) foot.append(el('div', { style: 'margin-bottom:6px' }, a.reason));
  if (a.corporateActionsDetected > 0) {
    foot.append(el('div.muted', { style: 'margin-top:5px' },
      `${a.corporateActionsDetected} bonus issue or share split was set aside before working out `
      + 'dilution. Those hand you more shares without taking anything from you, so counting them '
      + 'as dilution would be wrong.'));
  }
  foot.append(el('div.muted', { style: 'margin-top:5px' },
    'This does not change the score above it either.'));
  return el('div', {}, head, list, foot);
}

/**
 * The number beside a component, in the unit that component actually means.
 *
 * A missing figure renders as the shared "not measured" wording rather than a dash or a zero —
 * a zero payout and an untagged dividend line are completely different facts (Gotcha 21).
 */
function figureFor(c) {
  if (c.figure === null || c.figure === undefined) return '';
  switch (c.key) {
    case 'shareCount':
      return pct(c.figure) + '/yr';
    case 'payout':
      return pct(c.figure, { signed: false }) + ' out';
    case 'reinvestment':
      return num(c.figure, 1) + '×';
    case 'debt':
      return num(c.figure, 2) + '×';
    case 'incrementalReturn':
      return pct(c.figure, { signed: false });
    default:
      return num(c.figure, 1);
  }
}
