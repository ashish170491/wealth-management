/**
 * Quarterly results — the one renderer for every surface (SPEC 50.5).
 *
 * The verdict is decided server-side by QuarterlyResultRead.java and arrives on a row as
 * `resultVerdict` plus a dozen companions. Nothing is decided here: this file picks a wording and
 * a colour, so the rule table stays in one testable place — the same split as compounding.js,
 * macro-cells.js and analyst-cells.js, and the reason the portfolio and the stock page cannot end
 * up saying different things about one company's quarter.
 *
 * Three things this must never do.
 *
 * NOT_MEASURED and IN_LINE must never render alike. The first means no filed quarter has been
 * captured for this company — a gap in what the app has collected. The second means four checks
 * ran and the business was unremarkable, which is a finding. The second is far more reassuring,
 * which is exactly why collapsing them would be the dangerous direction (Gotcha 21, 44, 121).
 *
 * It must not present itself as a signal. A weak quarter is not a sell and a strong one is not a
 * buy: the vocabulary deliberately contains no instruction to transact (SPEC 20 rule 10), and the
 * reading changes no score anywhere in the app. Its use is the opposite — when a holding falls,
 * this is how the investor tells a business that is deteriorating from a price that is.
 *
 * And the date shown is the day the company PUBLISHED, never the quarter end. A quarter ending
 * 30-Jun is not public on 30-Jun; showing the period end would make every reading look six weeks
 * fresher than it is.
 */
import { el, badge, unmeasured } from './ui.js';
import { pct, pp, shortDate, displaySymbol, crore } from './format.js';

/** Verdict -> how it reads and how it looks. */
export const RESULT = {
  STRONG: { type: 'success', label: 'Strong' },
  IN_LINE: { type: 'neutral', label: 'In line' },
  WEAK: { type: 'warning', label: 'Weak' },
  CONCERNING: { type: 'danger', label: 'Concerning' },
};

/** Next-result status -> the short form for a cell. */
export const NEXT_RESULT = {
  AWAITING_QUARTER_END: 'quarter still running',
  EXPECTED: 'result due',
  PAST_DUE: 'result overdue',
};

/**
 * Sort order: the quarters that went backwards first — what is going wrong is what you came to
 * find — then in-line, then strong, with unmeasured always last.
 */
export function resultRank(row) {
  const v = row && row.resultVerdict;
  if (v === 'CONCERNING') return 0;
  if (v === 'WEAK') return 1;
  if (v === 'IN_LINE') return 2;
  if (v === 'STRONG') return 3;
  return 4;
}

/**
 * One table cell: the verdict, with the quarter beneath it so a stale reading is visible as
 * stale rather than looking current.
 */
export function resultCell(row) {
  const v = row && row.resultVerdict;

  if (!v || v === 'NOT_MEASURED') {
    return unmeasured((row && row.resultHeadline)
      || 'No quarterly result has been captured for this company yet — a gap in what the app has '
      + 'collected, not a statement that the company has not reported.');
  }

  const spec = RESULT[v];
  if (!spec) return unmeasured('Unrecognised reading');

  const node = badge(v, spec);
  node.title = tooltip(row);
  // The glossary tooltip would otherwise overwrite the sentence carrying the figures.
  node.setAttribute('data-no-gloss', '');

  const sub = [];
  if (row.resultQuarter) sub.push(row.resultQuarter);
  if (row.resultRevised) sub.push('restated');

  if (!sub.length) return node;
  return el('div', { style: 'display:flex;flex-direction:column;gap:2px' },
    node,
    el('span.faint', { style: 'font-size:11.5px' }, sub.join(' · ')));
}

function tooltip(row) {
  const bits = [];
  if (row.resultHeadline) bits.push(row.resultHeadline);
  const legs = [];
  if (row.resultRevenueYoyPercent != null) legs.push('sales ' + pct(row.resultRevenueYoyPercent));
  if (row.resultProfitYoyPercent != null) legs.push('profit ' + pct(row.resultProfitYoyPercent));
  if (row.resultMarginDeltaPp != null) legs.push('margin ' + pp(row.resultMarginDeltaPp));
  if (legs.length) bits.push('Year on year: ' + legs.join(', ') + '.');
  if (row.resultMeasuredSignals != null && row.resultTotalSignals != null) {
    bits.push(row.resultMeasuredSignals + ' of ' + row.resultTotalSignals + ' checks could be made.');
  }
  if (row.resultPublishedOn) bits.push('Published ' + shortDate(row.resultPublishedOn) + '.');
  if (row.resultRevised) bits.push('The company restated figures it had already published.');
  if (row.resultFrom && row.resultFrom !== row.symbol) {
    bits.push('Filed under ' + displaySymbol(row.resultFrom) + '.');
  }
  return bits.join(' ');
}

/**
 * The column, declared once.
 *
 * The header string lives here alone, because a column reading "Result" on one screen and
 * "Earnings" on another invites the reader to ask whether they are the same measurement
 * (Gotcha 85 applied to a heading). A short header also keeps the column narrow (SPEC 27.10).
 */
export function resultCol() {
  return {
    key: 'resultVerdict',
    label: 'Result',
    value: resultRank,
    render: resultCell,
  };
}

/**
 * The chip group. NOT_MEASURED gets its own chip on purpose — a reader has to be able to ask for
 * "the ones with no result captured" deliberately (Gotcha 117 rule b).
 */
export function resultFilterGroup() {
  return {
    label: 'Last result:',
    key: 'result',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'ATTENTION',
        text: 'Went backwards',
        test: (r) => r.resultVerdict === 'WEAK' || r.resultVerdict === 'CONCERNING',
        title: 'The last reported quarter went backwards on at least two of the four checks.',
      },
      { value: 'IN_LINE', text: 'In line', test: (r) => r.resultVerdict === 'IN_LINE' },
      { value: 'STRONG', text: 'Strong', test: (r) => r.resultVerdict === 'STRONG' },
      {
        value: 'NOT_MEASURED',
        text: 'None captured',
        test: (r) => !r.resultVerdict || r.resultVerdict === 'NOT_MEASURED',
        title: 'No filed quarter is on record here. A gap in the app, not a finding about the company.',
      },
    ],
  };
}

/**
 * The coverage line that has to sit under any table drawing this column.
 *
 * Without it an empty-looking Result column reads as "nothing is wrong", when it may mean
 * "nothing was checked" — the distinction this whole feature is built around (Gotcha 44).
 */
export function resultCoverageLine(rows) {
  const all = rows || [];
  if (!all.length) return null;
  const measured = all.filter((r) => r.resultVerdict && r.resultVerdict !== 'NOT_MEASURED').length;
  const attention = all.filter(
    (r) => r.resultVerdict === 'WEAK' || r.resultVerdict === 'CONCERNING').length;

  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    'A reported quarter was read for ' + measured + ' of ' + all.length + '; ' + attention
    + ' went backwards on at least two of the four checks. '
    + 'A row reading "not measured" has no filed quarter captured yet — that is a gap in the '
    + 'app’s own collection, not a finding about the company. A weak quarter is not a reason '
    + 'to sell; it is what tells you whether a falling price is the business or the market. '
    + 'None of this changes any score.');
}

// ------------------------------------------------------------------ stock page

const STATUS_MARK = {
  STRONG: { mark: '↑', cls: 'positive' },
  OK: { mark: '→', cls: 'faint' },
  WEAK: { mark: '↓', cls: 'negative' },
  NOT_MEASURED: { mark: '—', cls: 'faint' },
};

/**
 * The full reading for the stock page: all four checks including the ones that could not be made,
 * the headline figures, when the next result is due, and the footer that stops a weak quarter
 * being read as a sell instruction.
 */
export function resultPanel(data) {
  if (!data) return null;
  const v = data.resultVerdict;
  const signals = Array.isArray(data.resultSignals) ? data.resultSignals : [];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    v && v !== 'NOT_MEASURED' && RESULT[v]
      ? badge(v, RESULT[v])
      : unmeasured('No filed quarter captured for this company yet'),
    el('span', { style: 'font-weight:600;font-size:14px' },
      data.resultHeadline || 'No quarterly result on record.'));

  const body = el('div', {});

  if (v && v !== 'NOT_MEASURED') {
    const figures = [];
    if (data.revenueYoyPercent != null) figures.push(['Sales vs a year ago', pct(data.revenueYoyPercent)]);
    if (data.profitYoyPercent != null) figures.push(['Profit vs a year ago', pct(data.profitYoyPercent)]);
    if (data.marginDeltaPp != null) figures.push(['Margin change', pp(data.marginDeltaPp)]);
    if (data.resultRevenue != null) figures.push(['Sales', crore(data.resultRevenue)]);
    if (data.resultProfit != null) figures.push(['Profit', crore(data.resultProfit)]);

    if (figures.length) {
      const strip = el('div.row.wrap', { style: 'gap:18px;margin-bottom:12px' });
      for (const [label, value] of figures) {
        strip.append(el('div', {},
          el('div.faint', { style: 'font-size:11.5px' }, label),
          el('div', { style: 'font-weight:600;font-size:13.5px' }, value)));
      }
      body.append(strip);
    }
  }

  for (const s of signals) {
    const fx = STATUS_MARK[s.status] || STATUS_MARK.NOT_MEASURED;
    body.append(el('div', {
      style: 'display:flex;gap:10px;align-items:baseline;margin-bottom:9px;flex-wrap:wrap',
    },
    el('span.' + fx.cls, { style: 'font-weight:700;min-width:14px' }, fx.mark),
    el('div', { style: 'flex:1;min-width:260px' },
      el('div', { style: 'font-size:13.5px' }, s.label),
      el('div.muted', { style: 'font-size:12.5px;margin-top:2px' }, s.text || ''))));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });

  if (data.resultMeasuredSignals != null && data.resultTotalSignals != null
      && v && v !== 'NOT_MEASURED') {
    foot.append(el('div', { style: 'margin-bottom:6px' },
      data.resultMeasuredSignals + ' of ' + data.resultTotalSignals + ' checks could be made'
      + (data.resultComparedWith ? ', against ' + data.resultComparedWith : '')
      + (data.resultPublishedOn ? '. Published ' + shortDate(data.resultPublishedOn) : '') + '.'));
  }

  if (data.resultBasisNote) {
    foot.append(el('div.muted', { style: 'margin-bottom:6px' }, data.resultBasisNote));
  }

  if (data.nextResultText) {
    foot.append(el('div', { style: 'margin-bottom:6px' }, data.nextResultText));
  }

  foot.append(el('div.muted', {},
    'A weak quarter is not a reason to sell and a strong one is not a reason to buy. This is here '
    + 'so that when the price moves you can tell whether the business moved with it: a good '
    + 'company having one hard quarter for a reason you can name is a very different thing from '
    + 'one whose thesis has broken. None of this changes any score in the app.'));

  if (data.resultSymbolAnswered && data.resultSymbolAnswered !== data.symbol) {
    foot.append(el('div.faint', { style: 'margin-top:5px;font-size:11.5px' },
      'Filed under ' + displaySymbol(data.resultSymbolAnswered)
      + ' — a result describes the company, not the exchange it is quoted on.'));
  }

  return el('div', {}, head, body, foot);
}

/**
 * The quarter-by-quarter table for the stock page.
 *
 * Every row carries the basis it was filed on, because a series that silently mixes consolidated
 * and standalone shows a collapse and a recovery that never happened (Gotcha 73) — and the reader
 * is the only one who can spot it once the comparison has been refused.
 */
export function resultHistoryRows(data) {
  const history = Array.isArray(data && data.resultHistory) ? data.resultHistory : [];
  return history.map((q) => ({
    quarter: q.fiscalLabel,
    // The sort key, and it has to be the date. Sorting on the LABEL is a string sort, which puts
    // "Q4 FY25" above "Q3 FY26" and does not even put the newest quarter first — a history table
    // whose first row is not the latest reading is worse than no table. Caught by looking at a
    // screenshot; every server-side check and the syntax checker passed (Gotcha 89).
    quarterEnd: q.quarterEnd,
    revenue: q.revenue,
    profit: q.profit,
    netMargin: q.netMargin,
    eps: q.eps,
    basis: q.consolidated == null ? null : (q.consolidated ? 'Consolidated' : 'Standalone'),
    published: q.availableFrom,
    publishedEstimated: q.availableFromEstimated,
    revised: q.revised,
  }));
}
