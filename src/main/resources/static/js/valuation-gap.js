/**
 * "What does today's price require?" - the valuation-gap lens (SPEC 57).
 *
 * Everything shown here is computed server-side by ValuationGapService from stored data; nothing
 * is decided in this file, which only chooses wording and layout. Four things it must never do.
 *
 *  - Say what will happen or recommend anything. The panel states what would have to be true for
 *    today's price, against the business's own record. There is no range, no badge, no verdict
 *    word and no rupee figure for any future date anywhere on it.
 *  - Put the FY2031 trend comparison and the reference-multiple grid next to each other. They are
 *    two separate cards with another card between them, because a reader can multiply two numbers
 *    that sit side by side and the product would be a price target the app never computed.
 *  - Draw a refusal like "not measured". A refusal means the app looked and the data could not
 *    support a reading: a neutral badge and a full-weight sentence (Gotcha 121, 146). Only a
 *    figure that is absent draws the striped marker. A reason is never left in a tooltip.
 *  - Blend the reverse-DCF reading with the grid. Card F shows the stored figure and points to the
 *    existing "Is the price sensible?" panel; it is never combined with anything here (Gotcha 85).
 *
 * Vocabulary: the same words appear in ValuationReading.java and are pinned by
 * ValuationVocabularyTest, so a label added here that the server does not send is a mismatch.
 */
import { el, badge, unmeasured, table, costNote } from './ui.js';
import { post } from './api.js';
import { num, pp, shortDate, humanLabel, missing } from './format.js';

const STATUS = {
  MEASURED: { type: 'info', text: 'Read in full' },
  PARTIAL: { type: 'neutral', text: 'Read in part' },
  UNAVAILABLE: { type: 'neutral', text: 'Not read' },
};

function figure(v, digits = 1, suffix = '') {
  return missing(v) ? unmeasured('This figure is not available') : el('span.num', {}, `${num(v, digits)}${suffix}`);
}

/** A labelled card with its methodology printed under the title, so the two cards read as different things. */
function methodCard(title, methodology, ...body) {
  return el('div.card', { style: 'margin-bottom:14px' },
    el('div.cell-main', { style: 'font-weight:600;font-size:15px;margin-bottom:2px' }, title),
    methodology && methodology.terms
      ? el('div.muted', { style: 'font-size:12.5px;margin-bottom:10px' },
        `Method: ${methodology.terms.join(' · ')}`)
      : null,
    ...body);
}

function note(text) {
  return el('p.muted', { style: 'font-size:13px;margin:8px 0 0' }, text);
}

function refusal(label, sentence) {
  return el('div.info-box', {},
    el('div', { style: 'margin-bottom:6px' }, badge('UNAVAILABLE', { type: 'neutral', label })),
    el('div', {}, sentence));
}

// ------------------------------------------------------------------------------------- record

function seriesTable(earnings) {
  const rows = (earnings && earnings.series) || [];
  if (!rows.length) return null;
  return table([
    { key: 'fy', label: 'Year', render: (r) => el('span.num', {}, `FY${r.fy}`) },
    { key: 'netProfitCr', label: 'Net profit (₹ cr)', align: 'r', render: (r) => figure(r.netProfitCr, 0) },
    { key: 'shareCountCr', label: 'Shares (cr)', align: 'r', render: (r) => figure(r.shareCountCr, 2) },
    { key: 'shareFactor', label: 'Bonus/split factor', align: 'r',
      render: (r) => (missing(r.shareFactor) ? unmeasured() : el('span.num', {}, r.shareFactor === 1 ? '—' : `×${num(r.shareFactor, 2)}`)) },
    { key: 'epsAdjusted', label: 'EPS, adjusted (₹)', align: 'r', render: (r) => figure(r.epsAdjusted, 2) },
  ], rows, { sortKey: 'fy', sortDir: 'asc', filter: false });
}

function recordCard(r) {
  const e = r.earnings;
  const t = e && e.trend;
  const actions = (e && e.corporateActions) || [];
  const body = [seriesTable(e)];
  if (actions.length) {
    body.push(note(`${actions.length} bonus issue(s) or split(s) were divided out of earlier years: `
      + actions.map((a) => `FY${a.fy} ×${num(a.ratio, 2)} (${a.via === 'FACE_VALUE' ? 'face value changed' : 'share-count step'})`).join(', ')
      + '. They give existing owners more shares without taking anything from them.'));
  }
  if (t) {
    body.push(el('div.row', { style: 'gap:18px;margin-top:12px;flex-wrap:wrap' },
      stat('Trend growth', t.growthPct == null ? null : `${num(t.growthPct, 1)}% a year`),
      stat('Uncertainty (standard error)', t.stdErrPp == null ? null : `± ${num(t.stdErrPp, 1)} points`),
      stat('Years used', `${t.observations} (FY${t.firstFy}–FY${t.lastFy})`),
      stat('R-squared', t.rSquared == null ? null : num(t.rSquared, 2)),
      stat('First-to-last rate', t.endpointCagrPct == null ? null : `${num(t.endpointCagrPct, 1)}% a year`)));
    body.push(note(`Latest EPS is used as the starting point${e.normalisation && e.normalisation.flags && e.normalisation.flags.length
      ? ', except that it looked like a one-off peak, so the trend’s own value for that year is used instead' : ''}: `
      + `₹${num(e.normalisedEps, 2)} (normalised EPS). Growth is fitted on every contiguous year on one reporting basis, `
      + `and the uncertainty is shown beside it because ${t.observations} points support less than the headline rate suggests.`));
  }
  if (e && e.crossCheck && e.crossCheck.quarterlyEpsSum != null) {
    body.push(note(`The latest year’s EPS was checked against the four reported quarters (${num(e.crossCheck.quarterlyEpsSum, 2)} in total): `
      + `${e.crossCheck.passed ? 'they agree within 5%' : 'they do not agree'}. Earlier years are not checked this way.`));
  }
  return methodCard('The earnings record', null, ...body);
}

function stat(label, value) {
  return el('div', {},
    el('div.muted', { style: 'font-size:12px' }, label),
    el('div', { style: 'font-weight:600' }, value == null ? unmeasured() : value));
}

// ---------------------------------------------------------------- D: historical-trend comparison

function trendCard(r) {
  const c = r.trendComparison;
  if (!c) return null;
  const body = [
    el('p.muted', { style: 'margin:0 0 10px;font-size:13px' }, c.statement),
    el('div.row', { style: 'gap:18px;flex-wrap:wrap' },
      stat('Price ÷ normalised earnings today', c.pe0 == null ? null : `${num(c.pe0, 1)}×`),
      c.epsIfTrendContinues != null
        ? stat(`EPS in FY${c.referenceYear} if the past trend simply carried on`, `₹${num(c.epsIfTrendContinues, 2)}`)
        : null),
  ];
  if (c.epsIfTrendContinues == null && c.withheldReason) {
    body.push(refusal(humanLabel('TREND_UNRELIABLE'), c.withheldReason));
  }
  return methodCard('Historical-trend comparison', {
    terms: ['the record’s own fitted trend', 'shown only when its uncertainty is small', 'not stored anywhere'],
  }, ...body);
}

// ------------------------------------------------------------------------- E: required growth

function gridCard(r) {
  const g = r.requiredGrowthGrid;
  if (!g) return null;
  const rows = g.rows.map((x) => ({
    m: x.referenceMultiple,
    g: x.requiredGrowthPct,
    gap: x.gapToTrendPp,
    lo: x.atDiscount && x.atDiscount[0] ? x.atDiscount[0].requiredGrowthPct : null,
    hi: x.atDiscount && x.atDiscount[1] ? x.atDiscount[1].requiredGrowthPct : null,
    note: x.note,
  }));
  const withNotes = rows.filter((x) => x.note);
  return methodCard('Required-growth grid', g.methodology,
    el('p.muted', { style: 'margin:0 0 10px;font-size:13px' },
      'For today’s price to have earned 12% a year from price movement alone, to 31 March 2031, here is how fast '
      + 'earnings per share would have to grow if the market then paid each reference multiple. A what-if table: '
      + 'it states what would have to be true and says nothing about what will be.'),
    table([
      { key: 'm', label: 'Reference multiple', render: (x) => el('span.num', {}, `${x.m}×`) },
      { key: 'g', label: 'Growth needed a year', align: 'r', render: (x) => figure(x.g, 1, '%') },
      { key: 'gap', label: 'Against the record’s trend', align: 'r',
        render: (x) => (x.gap == null ? unmeasured('The record’s trend is too uncertain to compare against') : el('span.num', {}, pp(x.gap))) },
      { key: 'lo', label: 'If the hurdle were 10%', align: 'r', render: (x) => figure(x.lo, 1, '%') },
      { key: 'hi', label: 'If the hurdle were 14%', align: 'r', render: (x) => figure(x.hi, 1, '%') },
    ], rows, { sortKey: 'm', sortDir: 'asc', filter: false }),
    ...withNotes.map((x) => note(`${x.m}×: ${x.note}`)),
    el('div.info-box', { style: 'margin-top:10px' }, el('b', {}, 'About the reference multiples: '), g.limits));
}

// ------------------------------------------------------------------ F: existing reverse-DCF

function dcfCard(r) {
  const f = r.section125Reading;
  if (!f) {
    const b = r.blocks && r.blocks.F;
    return methodCard('Existing reverse-DCF reading', null,
      b && b.withheld ? refusal(humanLabel(b.reason), b.detail) : unmeasured('No stored reading'));
  }
  return methodCard('Existing reverse-DCF reading', f.methodology,
    el('div.row', { style: 'gap:18px;flex-wrap:wrap' },
      stat('Growth today’s price already assumes', `${num(f.impliedGrowthPct, 1)}% a year`)),
    note(f.note),
    note(`The full reading, with its comparison against what the company has actually delivered, is in the “${f.pointer}” panel. It is not repeated or recomputed here.`));
}

// ------------------------------------------------------------------------------------ the rest

function rulesList(r) {
  const rules = r.appliedRules || [];
  if (!rules.length) return null;
  return el('ul', { style: 'margin:6px 0 0 18px;font-size:13px' },
    ...rules.map((x) => el('li', {}, el('b', {}, `${x.id}: `), x.why)));
}

function snapshotLine(r) {
  const s = r.snapshots;
  if (!s) return null;
  return note(s.count
    ? `${s.count} validation record(s) stored for this stock, the newest written ${shortDate(s.latestCreatedAt)}. They store inputs and fit only, never a future figure, and exist so the method can later be checked against filed results. They are not a result.`
    : 'No validation record is stored for this stock yet. One is written by the 12:35 weekday job when a stock’s inputs change.');
}

/** Optional, off by default, and behind a confirmation: it costs a model call and only asks questions. */
function challenger(r) {
  const out = el('div', { style: 'margin-top:8px' });
  const btn = el('button.chip', { type: 'button' }, 'Ask the assumption challenger (optional)');
  btn.addEventListener('click', async () => {
    if (!window.confirm('This sends the computed reading to a language model, if one is configured, and shows any '
      + 'questions it raises about the assumptions. It can only ask questions: it never changes the reading and '
      + 'nothing it says is stored. It is switched off unless you have turned it on. Continue?')) return;
    btn.disabled = true;
    out.replaceChildren(el('span.muted', {}, 'Asking…'));
    try {
      const res = await post(`/api/valuation-gap/challenge?symbol=${encodeURIComponent(r.symbol)}`, undefined, 90000);
      const d = (res && res.data) || res || {};
      const list = (d.critiques || []).map((c) => el('li', {}, el('b', {}, `${humanLabel(c.target)} · ${humanLabel(c.kind)}: `), c.text));
      out.replaceChildren(
        el('div.muted', { style: 'font-size:13px' }, `${humanLabel(d.status)}${d.model ? ` · ${d.model}` : ''} — ${d.note || ''}`),
        list.length ? el('ul', { style: 'margin:6px 0 0 18px;font-size:13px' }, ...list) : null);
    } catch (err) {
      out.replaceChildren(el('span.muted', {}, String(err && err.message ? err.message : err)));
    } finally {
      btn.disabled = false;
    }
  });
  return el('div', { style: 'margin-top:10px' }, btn, out);
}

/**
 * The whole panel. Returns null when there is no reading at all (a 404: the app knows nothing of
 * this stock), so the caller can say "not in the app's tables" - a different fact from a refusal.
 */
export function valuationGapPanel(r) {
  if (!r || !r.status) return null;
  const st = STATUS[r.status] || STATUS.UNAVAILABLE;
  const header = el('div.row', { style: 'gap:10px;align-items:center;margin-bottom:12px;flex-wrap:wrap' },
    badge(r.status, { type: st.type, label: st.text }),
    r.valuationDate ? el('span.muted', { style: 'font-size:13px' },
      `Price from the screening of ${shortDate(r.valuationDate)}${r.price != null ? `: ₹${num(r.price, 2)}` : ''} — ${r.priceNote ? r.priceNote.toLowerCase() : ''}`) : null);

  const nodes = [header];

  if (r.status === 'UNAVAILABLE') {
    nodes.push(refusal(humanLabel(r.reasonCode), r.reason));
    const raw = r.earnings && r.earnings.series && r.earnings.series.length ? recordCard(r) : null;
    if (raw) nodes.push(el('p.muted', { style: 'font-size:13px' }, 'This is the record that was on file, shown so the reason can be checked:'), raw);
  } else {
    // Order is deliberate: the record, then D, then F, then E - so the trend comparison and the
    // reference-multiple grid never sit next to each other.
    nodes.push(recordCard(r), trendCard(r), dcfCard(r), gridCard(r));
    if (r.differenceBetweenMethods) {
      nodes.push(el('div.info-box', {},
        el('b', {}, 'Why the grid and the reverse-DCF reading are different cards: '), r.differenceBetweenMethods));
    }
    const rules = rulesList(r);
    if (rules) nodes.push(el('div', {}, el('b', {}, 'Rules applied to this stock'), rules));
  }

  if (r.coverageLine) nodes.push(note(r.coverageLine));
  if (r.caveats && r.caveats.length) {
    nodes.push(el('ul.muted', { style: 'margin:8px 0 0 18px;font-size:12.5px' }, ...r.caveats.map((c) => el('li', {}, c))));
  }
  nodes.push(snapshotLine(r));
  if (r.status !== 'UNAVAILABLE') {
    nodes.push(costNote('The optional challenger makes one model call. It is off unless switched on in settings.'));
    nodes.push(challenger(r));
  }
  return el('div', {}, ...nodes.filter(Boolean));
}

/** Heading text for the folded section: a count of the blocks that could be read, never a verdict. */
export function valuationGapSummary(r) {
  if (!r || !r.blocks) return null;
  const vals = Object.values(r.blocks);
  const available = vals.filter((b) => b === 'AVAILABLE').length;
  return `${available} of ${vals.length} parts`;
}
