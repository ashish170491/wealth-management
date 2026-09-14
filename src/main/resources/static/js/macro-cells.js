/**
 * Macro and geopolitical exposure — the one renderer for every surface (SPEC 48.10).
 *
 * The verdict is decided server-side by MacroExposureRead.java and arrives on a row as
 * `macroExposure` plus four companions. Nothing is decided here: this file picks a wording and a
 * colour, so the rule table stays in one testable place — the same split as compounding.js and
 * buy-timing.js, and the reason the portfolio, the screener, the watchlist, the stock page and
 * the Events page cannot end up saying different things about one stock.
 *
 * Three things this must never do.
 *
 * NOT_MEASURED and NOT_EXPOSED must never render alike. The first means the app has no rule for
 * this business yet — a gap in its own table. The second means it looked and nothing in the
 * window touches this business, which is a finding. Collapsing them would let a blind spot read
 * as an all-clear, which is the most dangerous thing this feature could do (Gotcha 21, 44).
 *
 * It must not present itself as a score. A headwind is not a sell and a tailwind is not a buy:
 * the vocabulary deliberately contains no instruction to transact (SPEC 20 rule 10), and the
 * reading changes no score anywhere in the app.
 *
 * And strength and size get their OWN label maps here, not format.js's. `LOW` and `MODERATE`
 * already mean "Well Spread" and "Moderately Concentrated" in the shared table — concentration
 * words — so routing a macro strength through humanLabel() would print "Well Spread" next to a
 * headwind.
 */
import { el, badge, unmeasured } from './ui.js';
import { humanLabel, displaySymbol, shortDate } from './format.js';

/** Verdict -> how it reads and how it looks. */
export const MACRO = {
  HEADWIND: { type: 'danger', label: 'Headwind' },
  TAILWIND: { type: 'success', label: 'Tailwind' },
  MIXED: { type: 'warning', label: 'Both ways' },
  NOT_EXPOSED: { type: 'neutral', label: 'Not affected' },
};

/** Local, for the reason in the header comment. Never route these through humanLabel(). */
export const STRENGTH = { HIGH: 'strong', MEDIUM: 'moderate', LOW: 'slight' };
export const MAGNITUDE = { SMALL: 'small', MODERATE: 'moderate', LARGE: 'large' };
export const DIRECTION = { UP: 'rose', DOWN: 'fell' };
export const KIND = { SCHEDULED: 'Expected', SURPRISE: 'Unexpected' };

/** A factor's human label. Rows carry `factorLabel` from the server; this is the fallback. */
export function factorLabel(row) {
  if (!row) return humanLabel(null);
  return row.factorLabel || humanLabel(row.factor);
}

/**
 * Sort order: headwinds first — what is working against you is what you came to find — then
 * both-ways, then tailwinds, then the stocks nothing touches, with unmeasured always last.
 */
export function macroExposureRank(row) {
  const v = row && row.macroExposure;
  if (v === 'HEADWIND') return 0;
  if (v === 'MIXED') return 1;
  if (v === 'TAILWIND') return 2;
  if (v === 'NOT_EXPOSED') return 3;
  return 4;
}

/**
 * One table cell. The reasons go in the tooltip so the column stays narrow; the full list is on
 * the stock page and on the Events page.
 */
export function macroExposureCell(row) {
  const v = row && row.macroExposure;

  if (!v || v === 'NOT_MEASURED') {
    return unmeasured((row && row.macroExposureNote)
      || 'The app has no exposure rule for this business yet — a gap in its own table, '
      + 'not a verdict on the company.');
  }

  const spec = MACRO[v];
  if (!spec) return unmeasured('Unrecognised reading');

  if (v === 'NOT_EXPOSED') {
    const node = badge(v, spec);
    node.title = 'Measured. Nothing recorded in the window touches this business — which is the '
      + 'ordinary answer, and a different thing from having no rule for it.';
    // The glossary tooltip would otherwise overwrite the sentence carrying the whole point.
    node.setAttribute('data-no-gloss', '');
    return node;
  }

  const strength = STRENGTH[row.macroExposureStrength];
  const node = badge(v, { type: spec.type, label: spec.label });
  node.title = tooltip(row, strength);
  node.setAttribute('data-no-gloss', '');

  if (!strength) return node;
  return el('div', { style: 'display:flex;flex-direction:column;gap:2px' },
    node,
    el('span.faint', { style: 'font-size:11.5px' }, strength));
}

function tooltip(row, strength) {
  const reasons = Array.isArray(row.macroExposureReasons) ? row.macroExposureReasons : [];
  const n = row.macroExposureEvents;
  const head = reasons.length ? reasons[0] : 'No reason recorded.';
  const more = reasons.length > 1 ? ' (+' + (reasons.length - 1) + ' more)' : '';
  const strong = strength ? ' Read as ' + strength + '.' : '';
  const from = row.macroExposureFrom && row.macroExposureFrom !== row.symbol
    ? ' Answered under ' + displaySymbol(row.macroExposureFrom) + '.' : '';
  const events = n ? ' ' + n + ' event' + (n === 1 ? '' : 's') + ' in the window apply to it.' : '';
  return head + more + strong + from + events;
}

/**
 * The column, declared once.
 *
 * Five screens draw this and the header string lives here alone, because a column reading
 * "Macro" on one screen and "Events" on another invites the reader to ask whether they are the
 * same measurement (Gotcha 85 applied to a heading). A short header also keeps the column
 * narrow, which is what SPEC 27.10 is about.
 */
export function macroExposureCol() {
  return {
    key: 'macroExposure',
    label: 'Macro',
    value: macroExposureRank,
    render: macroExposureCell,
  };
}

/**
 * The chip group. NOT_MEASURED and NOT_EXPOSED get their own chips on purpose — a reader has to
 * be able to ask for "the ones nobody has a rule for" deliberately (Gotcha 117 rule b).
 */
export function macroFilterGroup() {
  return {
    label: 'Macro:',
    key: 'macro',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'HEADWIND',
        text: 'Headwind',
        test: (r) => r.macroExposure === 'HEADWIND',
        title: 'Something recorded in the window makes the next few quarters harder for this business.',
      },
      { value: 'TAILWIND', text: 'Tailwind', test: (r) => r.macroExposure === 'TAILWIND' },
      { value: 'MIXED', text: 'Both ways', test: (r) => r.macroExposure === 'MIXED' },
      {
        value: 'NOT_EXPOSED',
        text: 'Nothing applies',
        test: (r) => r.macroExposure === 'NOT_EXPOSED',
        title: 'Measured, and nothing in the window touches it. The ordinary answer.',
      },
      {
        value: 'NOT_MEASURED',
        text: 'No rule yet',
        test: (r) => !r.macroExposure || r.macroExposure === 'NOT_MEASURED',
        title: 'The exposure map has no rule for this business. A gap in the app, not a verdict.',
      },
    ],
  };
}

/**
 * The coverage line that has to sit under any table drawing this column.
 *
 * Without it an empty-looking Macro column reads as "nothing is wrong", when it may mean
 * "nothing was checked" — the distinction this whole feature is built around (Gotcha 44).
 */
export function macroCoverageLine(rows, windowDays) {
  const all = rows || [];
  if (!all.length) return null;
  const measured = all.filter((r) => r.macroExposure && r.macroExposure !== 'NOT_MEASURED').length;
  const touched = all.filter((r) => ['HEADWIND', 'TAILWIND', 'MIXED'].includes(r.macroExposure)).length;
  const days = windowDays || 14;

  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    'Macro exposure judged for ' + measured + ' of ' + all.length + '; ' + touched + ' '
    + (touched === 1 ? 'is' : 'are') + ' touched by something recorded in the last ' + days
    + ' days. A row reading "not measured" has no rule in the exposure map yet — that is a gap '
    + 'in the app’s own table, not a finding about the company. None of this changes any score.');
}

// ------------------------------------------------------------------ stock page

const EFFECT = {
  TAILWIND: { mark: '↑', cls: 'positive', word: 'helps' },
  HEADWIND: { mark: '↓', cls: 'negative', word: 'hurts' },
  MIXED: { mark: '↔', cls: 'faint', word: 'cuts both ways for' },
};

/** Verdict -> the headline sentence on the stock page. */
function headline(v) {
  switch (v) {
    case 'HEADWIND':
      return 'Recent events outside this company make the next few quarters harder for it.';
    case 'TAILWIND':
      return 'Recent events outside this company make the next few quarters easier for it.';
    case 'MIXED':
      return 'Recent events cut both ways for this business, and they are not netted off.';
    case 'NOT_EXPOSED':
      return 'Nothing recorded recently touches this business.';
    default:
      return 'The app has no exposure rule for this business yet.';
  }
}

/**
 * The full reading for the stock page: every reason, with the channel it works through.
 *
 * The footer is fixed wording and must stay: it is the paragraph that stops a headwind being
 * read as a sell instruction.
 */
export function macroExposurePanel(data) {
  if (!data) return null;
  const v = data.macroExposure;
  const detail = Array.isArray(data.macroExposureReasonDetail) ? data.macroExposureReasonDetail : [];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    v && v !== 'NOT_MEASURED' && MACRO[v]
      ? badge(v, MACRO[v])
      : unmeasured('No rule in the exposure map for this business yet'),
    el('span', { style: 'font-weight:600;font-size:14px' }, headline(v)));

  const list = el('div', {});
  for (const d of detail) {
    const fx = EFFECT[d.effect] || EFFECT.MIXED;
    list.append(el('div', {
      style: 'display:flex;gap:10px;align-items:baseline;margin-bottom:9px;flex-wrap:wrap',
    },
    el('span.' + fx.cls, { title: fx.word, style: 'font-weight:700;min-width:14px' }, fx.mark),
    el('div', { style: 'flex:1;min-width:260px' },
      el('div', { style: 'font-size:13.5px' },
        factorLabel(d) + ' ' + (DIRECTION[d.eventDirection] || '')
        + (d.occurredAt ? ' on ' + shortDate(d.occurredAt) : '')),
      el('div.muted', { style: 'font-size:12.5px;margin-top:2px' },
        (d.channel || 'channel not recorded') + ' — ' + (STRENGTH[d.strength] || '')),
      d.rationale ? el('div.faint', { style: 'font-size:11.5px;margin-top:2px' }, d.rationale) : null)));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });

  if (v === 'NOT_MEASURED') {
    foot.append(el('div', { style: 'margin-bottom:6px' },
      data.macroExposureNote
      || 'No rule in the exposure map covers this business yet, so nothing can be said about it. '
      + 'That is a gap in the app’s own table rather than a finding about the company.'));
  } else if (v === 'NOT_EXPOSED') {
    const n = data.eventsConsidered || 0;
    foot.append(el('div', { style: 'margin-bottom:6px' },
      n + ' event' + (n === 1 ? '' : 's') + ' in the last ' + (data.windowDays || 14)
      + ' days were checked against this business and none of them applies. That is the '
      + 'ordinary answer on most days.'));
  }

  foot.append(el('div.muted', {},
    'A headwind is not a reason to sell and a tailwind is not a reason to buy. Their use is in '
    + 'reading the next set of results correctly: a good business having a hard quarter for a '
    + 'reason you can name is a very different thing from one whose thesis has broken. '
    + 'This does not change any score in the app.'));

  if (data.macroExposureFrom && data.macroExposureFrom !== data.symbol) {
    foot.append(el('div.faint', { style: 'margin-top:5px;font-size:11.5px' },
      'Answered under ' + displaySymbol(data.macroExposureFrom) + ' — the exposure map describes '
      + 'the company, not the exchange it is quoted on.'));
  }

  return el('div', {}, head, detail.length ? list : null, foot);
}
