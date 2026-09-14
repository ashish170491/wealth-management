/**
 * Per-stock drill-down. Every table row on every screen links here.
 *
 * This is the one page that touches the research endpoints, and the ONLY reason it can is
 * that exactly six of them are safe. Every other `GET /api/research/*` SENDS AN EMAIL
 * (SPEC 27.4), so the allowlist below is hard-coded as data rather than assembled from a
 * symbol — an accidental addition should be an obvious edit to a reviewed list, and api.js
 * refuses anything not on its own copy of that list as a second line of defence.
 */

import { get, getList, getOnDemand } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import {
  inr,
  inrExact,
  pct,
  num,
  humanLabel,
  displaySymbol,
  missing,
  shortDate,
  NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, badge, table, scoreBar, unmeasured, alert, costNote,
} from './ui.js';
import { lineChart, legend, radar, COLORS } from './charts.js';
import { compoundingPanel } from './compounding.js';
import { macroExposurePanel } from './macro-cells.js';
import { trackRecordPanel, capitalAllocationPanel } from './long-horizon.js';
import {
  statusCell, statusRank, ratingCell, targetCell, excessCell, daysToReachCell, horizonCell,
  shortHeadline, issuedCell, caveatBlock, plausibilityBlock,
} from './analyst-cells.js';

const view = document.getElementById('view');
const symbol = new URLSearchParams(window.location.search).get('symbol');

const DIMENSIONS = [
  { key: 'technicalMomentumScore', label: 'Momentum' },
  { key: 'volumeAccumulationScore', label: 'Volume' },
  { key: 'relativeStrengthScore', label: 'Rel. Strength' },
  { key: 'priceStructureScore', label: 'Structure' },
  { key: 'valuationScore', label: 'Valuation' },
  { key: 'institutionalInterestScore', label: 'Institutions' },
  { key: 'financialQualityScore', label: 'Financial Quality' },
];
// Sector Tailwind was the eighth axis until 2026-09-03 (SPEC 39.2). Removed from the composite
// and null on every row since; keeping it drew an empty axis that read as "not measured" forever.
// Captions below use DIMENSIONS.length so the count cannot drift from the list again.

/**
 * The six research endpoints that do NOT send an email. Verified against SPEC 27.4.
 * Do not add to this list without re-checking the endpoint's side effects.
 */
const RESEARCH = [
  { path: 'levels', label: 'Entry & exit levels', explain: 'Support, resistance, moving averages and what they imply for an entry price, stop-loss and targets.' },
  { path: 'valuation', label: 'Is the price sensible?', explain: 'Takes today’s price as given and works out what growth rate it assumes, then compares that against the company’s actual past growth.' },
  { path: 'capital-efficiency', label: 'Quality of the business', explain: 'How much profit the business makes from the money invested in it (ROCE, ROE), how much it has borrowed, and whether reported profit turns into real cash.' },
  { path: 'earnings', label: 'Earnings trend', explain: 'Revenue and profit growth over recent quarters, and whether growth is speeding up or slowing.' },
  { path: 'shareholding', label: 'Who owns it', explain: 'Whether the founders and large institutions have been buying or selling, and how much of the founders’ stake is pledged as loan collateral.' },
  { path: 'analyst', label: 'Analyst signal', explain: 'A proxy for broker opinion: whether the latest quarter broke from its own trend, plus any upgrades or downgrades in recent news.' },
];

// ------------------------------------------------------------------- header

function header(holding, score) {
  const name = displaySymbol(symbol);

  if (!holding) {
    return el('div', {},
      el('div.row.between.wrap', {},
        el('h1', { style: 'font-size:23px;color:var(--navy)' }, name),
        score ? badge(score.verdict) : null),
      alert({
        severity: 'INFO',
        title: 'You do not own this stock',
        message: 'Position and history figures below are only available for stocks you hold. Screening scores and research still work.',
      }));
  }

/**
 * The canonical signal for this stock (SPEC 6.6) - the same one the portfolio table shows.
 * Rendering `recommendation` raw here is what let BEL read BUY on this page and HOLD on the next.
 */
function signalBadge(holding) {
  const shown = holding.displaySignal || holding.recommendation;
  if (!shown) return null;
  const node = badge(shown);
  if (holding.signalNote) node.title = holding.signalNote;
  return node;
}

  return el('div', {},
    el('div.row.between.wrap', { style: 'margin-bottom:14px' },
      el('div', {},
        el('h1', { style: 'font-size:23px;color:var(--navy)' }, name),
        el('div.muted', { style: 'font-size:13px' },
          `${holding.industry || 'Industry not recorded'} · ${symbol}`)),
      el('div.row', { style: 'gap:7px' },
        signalBadge(holding),
        holding.trendDirection ? badge(holding.trendDirection) : null,
        score ? badge(score.grade, { type: String(score.grade || '').startsWith('A') ? 'success' : 'info', label: `grade ${score.grade}` }) : null)),
    el('div.grid.kpis', {},
      kpi({ label: 'You Hold', value: `${num(holding.quantity)} shares`, sub: `bought around ${inrExact(holding.averagePrice, true)}`, tone: 'neutral' }),
      kpi({ label: 'Current Price', value: inrExact(holding.currentPrice, true), raw: holding.dayChangePercent, tone: 'auto', sub: `today ${pct(holding.dayChangePercent)}` }),
      kpi({ label: 'Value', value: inr(holding.currentValue), sub: `${inr(holding.investedValue)} invested`, tone: 'neutral' }),
      kpi({ label: 'Your Gain / Loss', value: inr(holding.pnl), raw: holding.pnl, tone: 'auto', sub: pct(holding.pnlPercent) })));
}

// -------------------------------------------------------------------- charts

function priceHistory(points) {
  if (!points || points.length < 2) {
    return empty('Not enough recorded history',
      'This chart is built from the app’s own daily snapshots, which only exist for days it ran. It fills in over time.');
  }

  const series = [
    { name: 'Closing price', color: COLORS.navyLight, axis: 'left', points: points.map((p) => ({ x: p.d, y: p.close })), format: (v) => inrExact(v, true) },
    { name: 'Overall score', color: COLORS.warn, axis: 'right', points: points.map((p) => ({ x: p.d, y: Number.isFinite(p.overall) ? p.overall : null })), format: (v) => `${v}/100` },
  ];

  const chart = lineChart(series, { height: 280, formatLeft: (v) => inrExact(v, false), formatRight: (v) => String(Math.round(v)) });
  if (!chart) return empty('Nothing to chart', '');

  return card(chart, legend(series),
    el('div.muted', { style: 'font-size:12px;margin-top:6px' },
      'Breaks in a line are days with no snapshot — the app was not running. They are left as gaps rather than joined up, so the chart never invents a price.'));
}

function returnHistory(points) {
  if (!points || points.length < 2) return null;
  const series = [{
    name: 'Your return', color: COLORS.profit, fill: true,
    points: points.map((p) => ({ x: p.d, y: p.pnlPct })), format: (v) => pct(v),
  }];
  const chart = lineChart(series, { height: 200, yZeroLine: true, formatLeft: (v) => `${v.toFixed(0)}%` });
  return chart ? card(chart) : null;
}

function scoreTrend(trend) {
  if (!trend || trend.length < 2) {
    return empty('Not enough screening history',
      'The screener needs to have scored this stock on at least two different days to draw a trend.');
  }
  const series = [{
    name: 'Composite score', color: COLORS.navy,
    points: trend.map((t) => ({ x: t.screeningDate, y: t.compositeScore })), format: (v) => `${v}/100`,
  }];
  const chart = lineChart(series, { height: 200, formatLeft: (v) => String(Math.round(v)) });
  return chart ? card(chart) : empty('Nothing to chart', '');
}

function dimensionRadar(score) {
  if (!score) {
    return empty('This stock is not in the screening universe',
      'Only stocks the app screens get a seven-dimension score.');
  }
  const dims = DIMENSIONS.map((d) => ({ label: d.label, value: Number.isFinite(score[d.key]) ? score[d.key] : null }));
  const measured = dims.filter((d) => d.value !== null).length;

  return card(
    el('div.row.wrap', { style: 'gap:24px;align-items:center' },
      radar(dims, { size: 320 }) || el('div.muted', {}, 'Too few measured dimensions to draw a shape'),
      el('div', { style: 'flex:1;min-width:250px' },
        ...dims.map((d) => el('div.row.between', { style: 'font-size:13px;margin-bottom:5px' },
          el('span', { class: d.value === null ? 'faint' : '' }, d.label),
          d.value === null ? unmeasured('Not measurable for this stock') : el('span.row', { style: 'gap:8px' }, scoreBar(d.value, { width: 70 }), el('span.num', { style: 'min-width:24px;text-align:right' }, String(d.value))))),
        el('div', { style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule)' },
          el('div.row.between', { style: 'font-weight:600' },
            el('span', {}, 'Composite'),
            el('span', {}, `${num(score.compositeScore)} / 100`))),
        el('div.muted', { style: 'font-size:12px;margin-top:8px' },
          `${measured} of ${DIMENSIONS.length} dimensions measured. ${measured < DIMENSIONS.length ? 'Unmeasured ones are left out of both the shape and the composite rather than counted as zero.' : ''}`))));
}

function factors(score) {
  if (!score) return null;
  const split = (v) => (Array.isArray(v) ? v : String(v || '').split('|')).map((s) => String(s).trim()).filter(Boolean);
  const bull = split(score.bullishFactors);
  const bear = split(score.bearishFactors);
  if (!bull.length && !bear.length) return null;

  const list = (items, cls, title) => card(
    el('div', { style: `font-weight:600;margin-bottom:8px;color:var(--${cls})` }, title),
    items.length
      ? el('ul', { style: 'margin-left:18px;font-size:13px' }, items.map((i) => el('li', { style: 'margin-bottom:4px' }, i)))
      : el('div.muted', { style: 'font-size:13px' }, 'None recorded.'));

  return el('div.grid.two', {}, list(bull, 'profit', 'In its favour'), list(bear, 'loss', 'Against it'));
}

// ------------------------------------------------------------------- levels

function levelsPanel(holding) {
  if (!holding) return null;
  const rows = [
    ['Support (lower)', holding.support2],
    ['Support', holding.support1],
    ['Current price', holding.currentPrice],
    ['Resistance', holding.resistance1],
    ['Resistance (upper)', holding.resistance2],
  ].filter(([, v]) => Number.isFinite(v));

  if (rows.length < 2) {
    return empty('No price levels calculated', 'The app has not worked out support and resistance for this stock yet.');
  }

  const values = rows.map(([, v]) => v);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;

  const ladder = el('div', { style: 'margin-top:6px' });
  for (const [label, value] of rows) {
    const isPrice = label === 'Current price';
    const posPct = ((value - lo) / span) * 100;
    ladder.append(el('div', { style: 'margin-bottom:9px' },
      el('div.row.between', { style: `font-size:12.5px;${isPrice ? 'font-weight:600' : ''}` },
        el('span', { class: isPrice ? '' : 'muted' }, label),
        el('span.num', {}, inrExact(value, true))),
      el('div', { style: 'position:relative;height:7px;background:var(--rule);border-radius:4px;margin-top:3px' },
        el('div', { style: `position:absolute;left:${posPct.toFixed(1)}%;top:-2px;width:11px;height:11px;border-radius:50%;margin-left:-5px;background:${isPrice ? 'var(--navy)' : 'var(--ink-faint)'}` }))));
  }

  const extra = el('div.grid.three', { style: 'margin-top:14px' },
    kpi({ label: 'Suggested Stop', value: inrExact(holding.suggestedStopLoss, true), tone: 'negative' }),
    kpi({ label: 'Target 1', value: inrExact(holding.suggestedTarget1, true), tone: 'positive' }),
    kpi({ label: 'Target 2', value: inrExact(holding.suggestedTarget2, true), tone: 'positive' }));

  return card(ladder, extra);
}

// ----------------------------------------------------------- research (lazy)

function researchPanel() {
  const wrap = el('div', {});
  const buttons = el('div.row.wrap', { style: 'gap:8px' });
  const host = el('div', { style: 'margin-top:16px' });

  for (const r of RESEARCH) {
    const btn = el('button.action.secondary', {}, r.label);
    btn.addEventListener('click', async () => {
      buttons.querySelectorAll('button').forEach((b) => { b.disabled = true; });
      btn.textContent = 'Fetching…';
      host.replaceChildren(el('div.skeleton', { style: 'height:120px' }));
      try {
        const data = await getOnDemand(`/api/research/${r.path}/${encodeURIComponent(symbol)}`, 60000);
        host.replaceChildren(researchResult(r, data));
      } catch (e) {
        host.replaceChildren(alert({ severity: 'WARNING', title: `Could not load ${r.label.toLowerCase()}`, message: String(e.message || e) }));
      } finally {
        buttons.querySelectorAll('button').forEach((b) => { b.disabled = false; });
        btn.textContent = r.label;
      }
    });
    buttons.append(btn);
  }

  wrap.append(
    costNote('Each of these fetches fresh data from NSE or your broker and takes a few seconds. They only run when you press a button — nothing here loads automatically.'),
    buttons, host);
  return wrap;
}

/** Renders whatever a research endpoint returned, without assuming a fixed shape. */
function researchResult(meta, data) {
  if (!data || typeof data !== 'object') {
    return empty('No data returned', 'The endpoint responded but had nothing to report for this stock.');
  }

  const status = String(data.status || '').toUpperCase();
  if (status === 'UNAVAILABLE' || status === 'NO_DATA') {
    return alert({
      severity: 'INFO',
      title: 'Not available for this stock',
      message: data.message || data.reason || 'The data source had nothing for this stock. For insurers and some financials this is expected — they file in a format the app cannot read.',
    });
  }

  const SKIP = new Set(['status', 'symbol', 'elapsedMs', 'message']);
  const rows = Object.entries(data).filter(([k, v]) => !SKIP.has(k) && v !== null && v !== undefined && typeof v !== 'object');
  const lists = Object.entries(data).filter(([, v]) => Array.isArray(v) && v.length);
  const nested = Object.entries(data).filter(([, v]) => v && typeof v === 'object' && !Array.isArray(v));

  const body = el('div', {},
    el('div.info-box', {}, meta.explain));

  if (rows.length) {
    body.append(card(el('div', {}, ...rows.map(([k, v]) => el('div.row.between', { style: 'font-size:13px;padding:3px 0;border-bottom:1px solid #f2f2f2' },
      el('span.muted', {}, prettyKey(k)),
      el('span', { style: 'font-weight:600;text-align:right;max-width:62%' }, prettyValue(k, v)))))));
  }

  for (const [key, arr] of lists) {
    body.append(card(
      el('div', { style: 'font-weight:600;margin-bottom:7px' }, prettyKey(key)),
      el('ul', { style: 'margin-left:18px;font-size:13px' },
        arr.slice(0, 12).map((i) => el('li', { style: 'margin-bottom:3px' },
          typeof i === 'object' ? JSON.stringify(i) : String(i))))));
  }

  for (const [key, obj] of nested) {
    const entries = Object.entries(obj).filter(([, v]) => v !== null && v !== undefined && typeof v !== 'object');
    if (!entries.length) continue;
    body.append(card(
      el('div', { style: 'font-weight:600;margin-bottom:7px' }, prettyKey(key)),
      el('div', {}, ...entries.map(([k, v]) => el('div.row.between', { style: 'font-size:13px;padding:2px 0' },
        el('span.muted', {}, prettyKey(k)), el('span', { style: 'font-weight:600' }, prettyValue(k, v)))))));
  }

  if (data.methodologyNote || data.caveat) {
    body.append(el('div.cost-note', { style: 'margin-top:12px' }, data.caveat || data.methodologyNote));
  }
  return body;
}

function prettyKey(k) {
  return String(k)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/\bPercent\b/i, '%')
    .replace(/^./, (c) => c.toUpperCase());
}

function prettyValue(key, v) {
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (typeof v === 'number') {
    if (/percent|growth|margin|yield|rate/i.test(key)) return pct(v);
    if (/price|cap|cr\b|value|amount/i.test(key)) return inr(v);
    return num(v, Number.isInteger(v) ? 0 : 2);
  }
  const s = String(v);
  return /^[A-Z][A-Z_]+$/.test(s) ? humanLabel(s) : s;
}

// --------------------------------------------------------------------- boot

/**
 * Core status — the seven gates as a checklist (SPEC 35.2).
 *
 * The three-state gate is the whole point of showing it: "passed", "failed", and "there was
 * nothing to look at" are different answers, and a checklist that rendered the third as a tick
 * would tell the reader this stock had been checked when it had not.
 */
function coreStatus(view) {
  if (!view) {
    return empty('Not classified yet',
      'This stock has no core-holding classification. Classification runs each weekday morning '
      + 'across holdings only — a stock you do not own is not classified.');
  }

  const TIER = {
    CORE: { type: 'success', label: 'Core' },
    CORE_WATCH: { type: 'warning', label: 'Core · watch' },
    SATELLITE: { type: 'info', label: 'Satellite' },
    UNCLASSIFIED: { type: 'unmeasured', label: 'Not measured' },
  };
  const GATE = {
    PASS: { icon: '✓', tone: 'positive', what: 'checked, and it passed' },
    FAIL: { icon: '✗', tone: 'negative', what: 'checked, and it failed' },
    PASS_NO_DATA: { icon: '–', tone: 'unmeasured', what: 'nothing to check — this is not a pass on merit, and it does not count toward the five gates a core holding needs' },
    UNMEASURED: { icon: '–', tone: 'unmeasured', what: 'the input needed to judge this is missing' },
  };

  const head = el('div.kpis.grid', {},
    kpi({ label: 'Tier', value: (TIER[view.effectiveTier] || TIER.UNCLASSIFIED).label,
      tone: view.effectiveTier === 'CORE' ? 'positive' : view.effectiveTier === 'UNCLASSIFIED' ? 'unmeasured' : 'neutral' }),
    kpi({ label: 'Durability', value: missing(view.durabilityScore) ? 'Not measured' : String(view.durabilityScore),
      sub: view.durabilityCoverage || '', raw: view.durabilityScore, tone: 'neutral' }),
    kpi({ label: 'As of', value: shortDate(view.classifiedOn) }));

  const rows = table([
    { key: 'name', label: 'Check', render: (g) => el('span', {}, g.name) },
    {
      key: 'status',
      label: 'Result',
      render: (g) => {
        const spec = GATE[g.status] || GATE.UNMEASURED;
        if (spec.tone === 'unmeasured') return unmeasured(spec.what);
        return el('span', { class: spec.tone, title: spec.what }, `${spec.icon} ${humanLabel(g.status)}`);
      },
    },
    { key: 'reason', label: 'Why', sortable: false, render: (g) => el('span.muted', {}, g.reason || '') },
  ], view.gates || [], { sortKey: 'name', sortDir: 'asc', emptyMessage: 'No gate record stored for this classification.' });

  const notes = [];
  if (view.pendingChange) {
    notes.push(alert({ severity: 'WARNING', title: 'A tier change is waiting on confirmation',
      message: `${view.pendingChange}. Promotion and ordinary demotion need two weekly readings to `
        + 'agree, so a single bad day of data cannot flip how this stock is treated. A serious '
        + 'finding — an accounting red flag, a quality downgrade, a broken thesis — demotes it the same day.' }));
  }
  if (view.overrideApplied) {
    notes.push(alert({ severity: 'INFO', title: 'Your override is in force',
      message: `You set ${humanLabel(view.overrideApplied)} for this stock, and that outranks the `
        + 'gates above. It changes the tier and hides nothing: any red flag still appears in full.' }));
  }
  if ((view.missingInputs || []).length) {
    notes.push(card(el('div', {}, el('strong', {}, 'What could not be measured')),
      el('ul', { style: 'margin:6px 0 0;padding-left:18px' },
        ...view.missingInputs.filter(Boolean).map((m) => el('li.muted', {}, m)))));
  }

  return [head, rows, ...notes];
}

/** Compact watchlist status card (SPEC 37). */
function watchCard(w) {
  const grid = el('div.grid.kpis', {},
    kpi({ label: 'Added on', value: shortDate(w.addedOn), sub: missing(w.daysWatched) ? undefined : `${w.daysWatched} days ago` }),
    kpi({ label: 'Return since added', value: pct(w.returnSinceAddPct), raw: w.returnSinceAddPct, tone: 'auto',
      sub: missing(w.priceAtAdd) ? 'no price recorded at add time' : `from ${inrExact(w.priceAtAdd, true)}` }),
    kpi({ label: 'vs Nifty', value: missing(w.excessReturnPct) ? 'not measured' : `${w.excessReturnPct > 0 ? '+' : ''}${Number(w.excessReturnPct).toFixed(1)} pp`,
      raw: w.excessReturnPct, tone: 'auto', sub: w.niftyAsOf ? `Nifty as of ${shortDate(w.niftyAsOf)}` : undefined }),
    kpi({ label: 'Quality / Timing', value: `${missing(w.qualityScore) ? '—' : w.qualityScore} / ${missing(w.timingScore) ? '—' : w.timingScore}`,
      sub: w.qualityGrade ? `grade ${w.qualityGrade}` : (w.inUniverse ? 'quality: not screened yet' : 'quality: not in screening universe') }));
  const verdict = el('div', { style: 'margin-top:10px' }, badge(w.verdict), ' ',
    el('span.muted', {}, w.verdictReason || ''));
  const link = el('p', {}, el('a', { href: 'watchlist.html' }, 'Open the watchlist →'));
  return el('div', {}, grid, verdict, w.addedNote ? el('p', {}, el('b', {}, 'Your note: '), w.addedNote) : null, link);
}

/**
 * Brokerage targets on this stock, and what happened next (SPEC 49.8).
 *
 * Two refusals carry this panel. An empty list says "none reached our feeds", never "this stock
 * has no coverage" — the app reads four news feeds, not the sell-side. And the summary of open
 * targets is never called a consensus: it is however many quotable calls happened to be
 * published, which on most stocks is one or none, so the number of firms is printed beside it.
 */
function analystPanel(analyst) {
  const rows = (analyst && analyst.targets) || [];
  const live = (analyst && analyst.live) || null;

  if (rows.length === 0) {
    return empty('No brokerage target on record',
      'No broker target for this stock has appeared in the feeds this app reads. That is a fact about '
      + 'the feeds, not about how well the stock is covered — they carry the desks that publish into '
      + 'them, which is not every broker in the market.');
  }

  const head = live ? el('div.grid.kpis', {},
    kpi({
      label: 'Median open target',
      value: missing(live.medianTarget) ? NOT_MEASURED : inrExact(live.medianTarget),
      sub: live.houses === 1 ? 'from a single firm' : `from ${num(live.houses)} firms`,
      tone: missing(live.medianTarget) ? 'unmeasured' : 'neutral',
    }),
    kpi({
      label: 'Implied from the last stored price',
      value: missing(live.impliedUpsidePct) ? NOT_MEASURED : pct(live.impliedUpsidePct),
      raw: live.impliedUpsidePct,
      tone: 'auto',
      sub: live.priceAsOf ? `price as of ${shortDate(live.priceAsOf)}` : 'no stored price',
    }),
    kpi({ label: 'Targets still running', value: num(live.openTargets), sub: 'horizon not yet reached' })) : null;

  const note = live && live.note ? el('p.muted', {}, live.note) : null;

  const tbl = table([
    { key: 'issuedOn', label: 'Date', value: (r) => r.issuedOn || '', render: issuedCell },
    { key: 'brokerage', label: 'Brokerage', render: (r) => el('span', {}, r.brokerage) },
    { key: 'rating', label: 'They said', render: ratingCell },
    { key: 'targetPrice', label: 'Target', value: (r) => r.targetPrice ?? -1, render: targetCell },
    { key: 'horizonDays', label: 'Horizon', value: (r) => r.horizonDays ?? 0, render: horizonCell },
    { key: 'status', label: 'What happened', value: statusRank, render: statusCell },
    { key: 'daysToReach', label: 'Days to get there', value: (r) => r.daysToReach ?? 1e9, render: daysToReachCell },
    { key: 'excessReturnPct', label: 'vs Nifty', value: (r) => r.excessReturnPct ?? -1e9, render: excessCell },
    { key: 'headline', label: 'Headline', render: shortHeadline },
  ], rows, { sortKey: 'issuedOn', sortDir: 'desc', filter: false });

  // The plausibility panel sits between the summary and the ledger deliberately: a reader who
  // has just been shown a median target and an implied upside is at exactly the moment of
  // deciding whether to believe it, and this is the only thing on the page that can speak to
  // that (SPEC 49.13). The raw calls follow, so the check precedes the claims it checks.
  const plausible = plausibilityBlock(analyst && analyst.plausibility);

  return el('div', {}, caveatBlock(analyst && analyst.caveat), head, note,
    plausible ? el('div', { style: 'margin-top:18px' }, plausible) : null,
    el('div', { style: 'margin-top:18px' }, tbl));
}

async function boot() {
  if (!symbol) {
    mount(view, section('No stock selected', '', empty('Missing symbol', 'Open this page from a stock link on one of the other screens.')));
    return;
  }

  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, `Loading ${displaySymbol(symbol)}…`), skeleton(4)));
  await initChrome();

  const enc = encodeURIComponent(symbol);
  const [holding, series, trend, recs, coreHistory, watch, compounding, longHorizon,
    macro, analyst] = await Promise.all([
    get(`/api/trading/holdings/${enc}`, { fallback: null }).then((r) => r.data).catch(() => null),
    get(`/api/dashboard/series/holding?symbol=${enc}&days=1095`, { fallback: [] }).then((r) => r.data).catch(() => []),
    getList(`/api/multibagger/trend/${enc}?days=365`).then((r) => r.data).catch(() => []),
    getList(`/api/accuracy/by-symbol/${enc}`).then((r) => r.data).catch(() => []),
    // DB-only (holding_classification), verified by hand — a page-load get() is ungated.
    getList(`/api/portfolio/core-holdings/history?symbol=${enc}&days=180`).then((r) => r.data).catch(() => []),
    // DB-only (watchlist + snapshots), verified by hand. 404 when not on the list → null.
    get(`/api/watchlist/item?symbol=${enc}`, { fallback: null, cache: false }).then((r) => r.data).catch(() => null),
    // DB-only (latest multibagger_scores row + one grouped count), verified by hand. 404 when
    // the stock has never been screened → null, which renders as "never screened".
    get(`/api/dashboard/compounding?symbol=${enc}`, { fallback: null }).then((r) => r.data).catch(() => null),
    // DB-only (annual_fundamentals + one multibagger_scores lookup for the lender flag),
    // verified by hand — a page-load get() is ungated, only getOnDemand has the allowlist
    // (Gotcha 39). Returns a NOT_MEASURED record rather than 404 when there is no history,
    // so "nobody has read this company's accounts yet" never renders as "poor record".
    get(`/api/fundamentals/long-horizon?symbol=${enc}`, { fallback: null }).then((r) => r.data).catch(() => null),
    // DB + classpath only (the event ledger and the exposure map), verified by hand. 404 when
    // the stock has never been screened, which is a different answer from a 200 carrying
    // NOT_MEASURED — the first means the app cannot look this business up at all, the second
    // that it looked and has no rule for it (SPEC 48.8).
    get(`/api/macro/exposure?symbol=${enc}`, { fallback: null }).then((r) => r.data).catch(() => null),
    // DB-only (analyst_targets), verified by hand. Returns an empty target list rather than a
    // 404 when nothing has been recorded — "no analyst target reached our feeds" and "this stock
    // is not covered" are different facts, and the panel below says which one it is (SPEC 49.8).
    get(`/api/analyst/targets?symbol=${enc}`, { fallback: null }).then((r) => r.data).catch(() => null),
  ]);

  const score = (trend && trend.length) ? trend[trend.length - 1] : null;
  const nodes = [];

  nodes.push(el('div.section', {}, header(holding, score)));

  if (watch && watch.active) {
    nodes.push(section('On your watchlist',
      'You added this stock to your watchlist on the date shown. The return is measured from the price that day; the verdict combines the business-quality score with today’s chart. Manage it on the Watchlist page.',
      watchCard(watch)));
  }

  nodes.push(section('Price and score over time',
    'The app’s own daily record of this stock: closing price on the left axis, its overall score on the right. When price and score move apart it is worth asking which one is early.',
    priceHistory(series),
    returnHistory(series)));

  if (holding) {
    nodes.push(section('Key price levels',
      'Support is a level where buyers have stepped in before; resistance is where sellers have. Price sitting just under resistance often stalls; clearing it on strong volume is usually a better entry than buying into it.',
      levelsPanel(holding)));
  }

  if (holding) {
    const latestCore = (coreHistory && coreHistory.length) ? coreHistory[coreHistory.length - 1] : null;
    nodes.push(section('Should you ever sell this one?',
      'Seven checks on the business behind the stock — how well it earns on the capital in it, how '
      + 'solid the balance sheet is, how steady earnings are, whether the accounts throw up red '
      + 'flags, whether your reason for buying still holds, what insiders are doing, and the '
      + 'horizon you recorded. Price is deliberately not among them. A check marked not measured '
      + 'is not a pass: it means there was nothing to look at, and it earns the stock nothing.',
      coreStatus(latestCore)));
  }

  nodes.push(section('Can this business compound?',
    'The question this app exists for, and the one the score above is worst at answering — most '
    + 'of that score is about how the share price has behaved. These five checks are about the '
    + 'business instead: what it earns on the money in it, whether that profit is real cash, '
    + 'whether growth is self-funded, and whether it is steady. A check that could not be '
    + 'measured is shown as such and counts for nothing, either way.',
    compoundingPanel(compounding)
      || empty('Never screened', 'This stock is not in the screening universe, so its accounts have not been read.')));

  nodes.push(section('Which recent events matter to this business?',
    'Things that happen outside a company — an interest-rate decision, a tariff, the oil price, '
    + 'the rupee, the monsoon — make the next few quarters easier or harder for whole industries. '
    + 'This is what the app has recorded recently and which of it reaches this particular '
    + 'business, with the channel it works through so you can disagree with the reasoning. A '
    + 'headwind is not a reason to sell and a tailwind is not a reason to buy, and none of it '
    + 'changes the score above.',
    macroExposurePanel(macro)
      || empty('Never screened',
        'This stock is not in the screening universe, so the app has not classified its business '
        + 'and cannot look it up in the exposure map.')));

  nodes.push(section('Has it actually compounded?',
    'The section above asks whether this business CAN compound, from its most recent year. This '
    + 'one asks whether it HAS, across every year of accounts on file. They are different '
    + 'questions: one good year is what every cyclical business shows at the top of its cycle, so '
    + 'a record is the stronger evidence. Each check shows how many years it held up in, out of '
    + 'the years that could be measured — a count, not an average, because an average lets one '
    + 'boom year carry a decade.',
    trackRecordPanel(longHorizon, compounding)
      || empty('No accounts on file',
        'The app has not read this company’s annual accounts yet. It works through the list a few '
        + 'stocks a day, so this should fill in over the coming weeks.')));

  nodes.push(section('What has management done with your money?',
    'Profits a company keeps are yours, reinvested on your behalf. This is the record of what was '
    + 'done with them: whether new shares were issued and watered down your stake, how much was '
    + 'paid out versus kept, whether it kept building, whether growth was funded with borrowing, '
    + 'and what each extra rupee of capital actually earned. Bonus issues and splits are set aside '
    + 'first — they hand you more shares without taking anything from you.',
    capitalAllocationPanel(longHorizon)
      || empty('No accounts on file',
        'This needs at least five years of annual accounts, which have not been read for this '
        + 'company yet.')));

  nodes.push(section('How it scores across seven dimensions',
    'The screener’s breakdown. A lopsided shape means the overall score rests on just a few dimensions. Anything the app could not measure for this stock is left out entirely rather than scored as zero — which would understate it.',
    dimensionRadar(score),
    factors(score)));

  nodes.push(section('Has the score been holding up?',
    'The composite score over the last year. A steady decline is thesis drift — the app’s reasons for liking the stock weakening, which often shows before the price reacts.',
    scoreTrend(trend)));

  if (recs && recs.length) {
    nodes.push(section('Times the app recommended this stock',
      'Every past recommendation, with the score and price at the time. Useful for checking whether the app has been consistently right or consistently early on this one.',
      table([
        { key: 'issuedDate', label: 'Date', render: (r) => shortDate(r.issuedDate) },
        { key: 'source', label: 'Engine', render: (r) => humanLabel(r.source) },
        { key: 'score', label: 'Score', align: 'r', render: (r) => num(r.score) },
        { key: 'grade', label: 'Grade', render: (r) => (r.grade ? badge(r.grade, { type: 'info', label: r.grade }) : unmeasured()) },
        { key: 'verdict', label: 'Verdict', render: (r) => badge(r.verdict) },
        { key: 'issuedPrice', label: 'Price Then', align: 'r', render: (r) => inrExact(r.issuedPrice, true) },
        { key: 'targetPrice', label: 'Target', align: 'r', render: (r) => (missing(r.targetPrice) ? unmeasured('This engine sets no target') : inrExact(r.targetPrice, true)) },
      ], recs, { sortKey: 'issuedDate' })));
  }

  nodes.push(section('What have the brokerages said, and were they right?',
    'Price targets published by brokerages, with what the share price actually did afterwards. A target is one firm’s opinion on a one-year view — it is recorded here so it can be checked, and it changes nothing in this app’s own scoring.',
    analystPanel(analyst)));

  nodes.push(section('Dig deeper',
    'Fresh research pulled on demand from NSE filings and news. Each button fetches live data, so they run only when you ask.',
    researchPanel()));

  mount(view, nodes);
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load this stock', String(err && err.message ? err.message : err))));
});
