/**
 * Track Record — does the app's own scoring actually work?
 *
 * The most jargon-dense screen in the dashboard, so it gets the heaviest glossing. Two
 * honesty rules dominate the design:
 *
 *  1. A cell with too few picks shows its sample size and NOTHING ELSE. A 100% hit rate
 *     from 2 picks renders identically to one from 300 otherwise (SPEC 21 rule 7).
 *  2. Per-dimension IC hits the broker despite being a GET, so it is click-only with an
 *     explicit cost warning — never fetched on page load (SPEC 27.4).
 */

import { get, getOnDemand, coerceNumbers } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import {
  pct, num, corr, humanLabel, missing, sign, shortDate, stockHref, displaySymbol,
  inrExact, NOT_MEASURED,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, table, alert, unmeasured, costNote, badge,
  collapse, withCount, scoreBar,
} from './ui.js';
import { barChart } from './charts.js';
import {
  statusCell, statusRank, ratingCell, targetCell, excessCell, daysToReachCell, horizonCell,
  hitRateCell, shortHeadline, issuedCell, caveatBlock,
} from './analyst-cells.js';

const view = document.getElementById('view');

/** Below this many picks, the numbers are withheld rather than shown misleadingly. */
const MIN_SAMPLE = 10;

/** The conventional threshold above which an Information Coefficient is considered useful. */
const USEFUL_IC = 0.10;

const SOURCES = ['MULTIBAGGER', 'QUANT_DISCOVERY', 'SECTOR_REVERSAL'];
const HORIZONS = [30, 90, 180, 365];

let stats = [];
let coverage = [];
let analystRecord = null;
let analystRecent = [];
let analystRecentTotal = 0;
let overlap = null;
let analystRecentDays = 365;

// ------------------------------------------------------- the analysts' record (SPEC 49)

/**
 * How brokerage price targets have actually done.
 *
 * This sits on the same page as the app's own record on purpose: it is the same question asked
 * of somebody else, measured on the same yardstick (excess return over the Nifty across the same
 * dates). It is also the page's biggest opportunity to mislead, so the caveat block is not
 * optional furniture — the sample is the desks that publish into this feed, not all of Indian
 * equity research, and it is four-fifths Buy.
 */
function analystHouses() {
  const houses = (analystRecord && analystRecord.houses) || [];
  if (houses.length === 0) {
    return empty('No analyst targets recorded yet',
      'The app records each published broker target — the firm, the price and the date — from a feed '
      + 'that indexes brokers’ own research notes, and from news headlines where that feed has no '
      + 'entry. Nothing has been captured yet, which is a fact about the feeds rather than about how '
      + 'much research is being published.');
  }

  return table([
    { key: 'brokerage', label: 'Brokerage', render: (r) => el('span', {}, r.brokerage) },
    { key: 'total', label: 'Calls', value: (r) => r.total, render: (r) => el('span.num', {}, num(r.total)) },
    { key: 'resolved', label: 'Run their course', value: (r) => r.resolved, render: (r) => el('span.num', {}, num(r.resolved)) },
    { key: 'hitRatePercent', label: 'Price got there', value: (r) => r.hitRatePercent ?? -1, render: hitRateCell },
    {
      key: 'medianExcessReturnPct',
      label: 'vs Nifty (median)',
      value: (r) => r.medianExcessReturnPct ?? -1e9,
      render: (r) => (missing(r.medianExcessReturnPct)
        ? unmeasured('Too few calls have run their course to say anything')
        : el('span.num' + (r.medianExcessReturnPct > 0 ? '.positive' : r.medianExcessReturnPct < 0 ? '.negative' : ''),
          { title: 'Signed the way the call was made, so a stock that fell after a Sell call counts in the house’s favour.' },
          pct(r.medianExcessReturnPct))),
    },
    {
      key: 'medianDaysToReach',
      label: 'Days to get there',
      value: (r) => r.medianDaysToReach ?? 1e9,
      render: (r) => (missing(r.medianDaysToReach) ? el('span.muted', {}, '—')
        : el('span.num', {}, `${num(r.medianDaysToReach)} d`)),
    },
    {
      key: 'medianClaimedUpsidePct',
      label: 'Upside claimed',
      value: (r) => r.medianClaimedUpsidePct ?? -1e9,
      render: (r) => (missing(r.medianClaimedUpsidePct)
        ? unmeasured('No call could be priced against the day it was made')
        : el('span.num', { title: 'How much upside this house typically claims. Context for the hit rate beside it, not a performance figure.' },
          pct(r.medianClaimedUpsidePct))),
    },
    {
      key: 'revisionRatePercent',
      label: 'Revised before due',
      value: (r) => r.revisionRatePercent ?? -1,
      render: (r) => (missing(r.revisionRatePercent) ? unmeasured('No calls on file')
        : el('span.num', { title: 'A revised target is not counted as a miss — the house withdrew it — but a house that revises just before the deadline would otherwise escape every miss it ever made. So it is counted here instead.' },
          pct(r.revisionRatePercent, { signed: false }))),
    },
    { key: 'pending', label: 'Still running', value: (r) => r.pending, render: (r) => el('span.num', {}, num(r.pending)) },
  ], houses, { sortKey: 'resolved', sortDir: 'desc', filter: false });
}

/** The coverage line. The honest answer to "is that hit rate worth reading" is mostly here. */
function analystCoverage() {
  const c = analystRecord && analystRecord.coverage;
  if (!c) return null;
  return el('div.grid.kpis', {},
    kpi({ label: 'Targets recorded', value: num(c.targets), sub: `on ${num(c.stocks)} stocks, from ${num(c.brokerages)} brokerages` }),
    // Deliberately NOT the unmeasured tone at zero. Nothing having resolved yet is a measured
    // fact about the ledger, not a figure the app failed to compute, and greying it out would
    // spend the one visual language reserved for "we could not measure this" (SPEC 21 rule 7).
    kpi({
      label: 'Run their course',
      value: num(c.resolved),
      sub: c.resolved === 0 ? 'nothing has resolved yet' : 'measurable hits and misses',
    }),
    kpi({ label: 'Still running', value: num(c.stillRunning), sub: 'counted as neither hit nor miss' }),
    kpi({ label: 'Revised before due', value: num(c.revised), sub: 'withdrawn by the house, kept on the ledger' }));
}

/** Every recorded target, newest first. Folded by default — the count is what makes that safe. */
function analystRecentTable() {
  return table([
    { key: 'issuedOn', label: 'Date', value: (r) => r.issuedOn || '', render: issuedCell },
    {
      key: 'symbol',
      label: 'Stock',
      render: (r) => el('a', { href: stockHref(r.symbol) }, displaySymbol(r.symbol)),
    },
    { key: 'brokerage', label: 'Brokerage', render: (r) => el('span', {}, r.brokerage) },
    { key: 'rating', label: 'They said', render: ratingCell },
    { key: 'targetPrice', label: 'Target', value: (r) => r.targetPrice ?? -1, render: targetCell },
    { key: 'horizonDays', label: 'Horizon', value: (r) => r.horizonDays ?? 0, render: horizonCell },
    { key: 'status', label: 'What happened', value: statusRank, render: statusCell },
    { key: 'daysToReach', label: 'Days to get there', value: (r) => r.daysToReach ?? 1e9, render: daysToReachCell },
    { key: 'excessReturnPct', label: 'vs Nifty', value: (r) => r.excessReturnPct ?? -1e9, render: excessCell },
    { key: 'headline', label: 'Headline', render: shortHeadline },
  ], analystRecent, { sortKey: 'issuedOn', sortDir: 'desc' });
}

// ------------------------------------------------- where we and the analysts disagree (SPEC 49.12)

/** One row of the joined view: our score beside what the brokerages are quoting. */
function overlapColumns() {
  return [
    {
      key: 'symbol',
      label: 'Stock',
      render: (r) => el('a', { href: stockHref(r.symbol) }, displaySymbol(r.symbol)),
    },
    { key: 'composite', label: 'Our score', value: (r) => r.composite ?? -1, render: (r) => scoreBar(r.composite) },
    {
      key: 'qualityVerdict',
      label: 'Our quality',
      render: (r) => (r.qualityVerdict ? badge(r.qualityVerdict, { label: humanLabel(r.qualityVerdict) }) : unmeasured()),
    },
    { key: 'houses', label: 'Firms', value: (r) => r.houses ?? 0, render: (r) => el('span.num', {}, num(r.houses)) },
    {
      key: 'medianTarget',
      label: 'Median target',
      value: (r) => r.medianTarget ?? -1,
      render: (r) => (missing(r.medianTarget) ? unmeasured() : el('span.num', {}, inrExact(r.medianTarget))),
    },
    {
      key: 'upsideToMedianPct',
      label: 'Claimed upside',
      value: (r) => r.upsideToMedianPct ?? -1e9,
      render: (r) => (missing(r.upsideToMedianPct)
        ? unmeasured()
        : el('span.num' + (r.upsideToMedianPct > 0 ? '.positive' : '.negative'), {}, pct(r.upsideToMedianPct))),
    },
    {
      key: 'forensicFlags',
      label: 'Our red flags',
      render: (r) => (r.forensicFlags
        ? el('span.warn', { title: r.forensicFlags }, r.forensicFlags.split(';')[0])
        : el('span.muted', {}, '—')),
    },
  ];
}

/** Coverage of our own score bands by the brokerages. */
function overlapBands() {
  const bands = (overlap && overlap.bands) || [];
  if (bands.length === 0) return null;
  return table([
    { key: 'label', label: 'Our score band', render: (r) => el('span', {}, r.label) },
    { key: 'stocks', label: 'Stocks', value: (r) => r.stocks, render: (r) => el('span.num', {}, num(r.stocks)) },
    {
      key: 'withOpenTarget',
      label: 'With a live target',
      value: (r) => r.withOpenTarget,
      render: (r) => el('span.num', {}, num(r.withOpenTarget)),
    },
    {
      key: 'coveragePercent',
      label: 'Covered',
      value: (r) => r.coveragePercent ?? -1,
      // An empty band reports nothing, not 0% — no stock was there to cover.
      render: (r) => (missing(r.coveragePercent) ? unmeasured('No stock in this band')
        : el('span.num', {}, pct(r.coveragePercent, { signed: false }))),
    },
  ], bands, { sortKey: null, filter: false });
}

/**
 * The paragraphs that must sit beside these numbers.
 *
 * `info-box` is the SPEC 21 "what this means" box and is the only prose class that exists in
 * app.css — this block asked for `.callout` and `ul.plain`, neither of which is defined, so it
 * rendered as unstyled body text and read as filler (Gotcha 104: a class that does not exist
 * fails exactly like a blocked style attribute — silently, with the attribute present).
 */
function overlapNotes() {
  const n = (overlap && overlap.notes) || null;
  if (!n) return null;
  const list = el('ul');
  for (const [, text] of Object.entries(n)) {
    if (typeof text === 'string' && text.trim()) list.append(el('li', {}, text));
  }
  return list.firstChild ? el('div.info-box', {}, list) : null;
}

/**
 * What to actually do with the lists below.
 *
 * Deliberately first among the prose: the explanation of *why* our score and a broker's target
 * disagree is the honest context, but it is not a next step, and a screen that offers only
 * context gets read once. Every line and every count comes from the server (`AnalystOverlap`),
 * so the rule deciding what is worth acting on stays in Java beside its test.
 */
function overlapActions() {
  const actions = (overlap && overlap.actions) || [];
  if (actions.length === 0) return null;
  const list = el('ul');
  for (const a of actions) {
    if (!a || !a.title) continue;
    list.append(el('li', {}, el('b', {}, a.title), ' — ', a.guidance || ''));
  }
  return list.firstChild
    ? el('div.card', {}, el('b', {}, 'What to do with this'), list)
    : null;
}

function overlapKpis() {
  if (!overlap) return null;
  const c = overlap.upsideVsCompositeCorrelation;
  return el('div.grid.kpis', {},
    kpi({
      label: 'Our good stocks',
      value: num(overlap.goodStocks),
      sub: `rated Potential or Strong on ${shortDate(overlap.screeningDate)}`,
    }),
    kpi({
      label: 'Of those, a firm is quoting a target',
      value: num(overlap.goodWithOpenTarget),
      sub: overlap.goodStocks
        ? `${Math.round(100 * overlap.goodWithOpenTarget / overlap.goodStocks)}% of them`
        : 'none screened',
    }),
    kpi({
      label: 'Claimed upside vs our score',
      value: missing(c) ? NOT_MEASURED : corr(c),
      raw: c,
      tone: missing(c) ? 'unmeasured' : 'neutral',
      sub: missing(c) ? 'nothing to correlate' : `across ${num(overlap.correlationSample)} stocks — see why below`,
    }));
}

// --------------------------------------------------------------- headline

function headline() {
  const usable = stats.filter((s) => s.sampleSize >= MIN_SAMPLE);
  if (usable.length === 0) {
    return alert({
      severity: 'INFO',
      title: 'No conclusions available yet',
      message: `Scoring a pick means waiting for it to reach 30, 90, 180 or 365 days and comparing it against the Nifty 50. Nothing has enough measured picks yet (the threshold is ${MIN_SAMPLE}), so no numbers are shown — an early hit rate from a handful of picks is noise, not evidence.`,
    });
  }

  const best = usable.reduce((a, b) => ((b.meanExcessReturnPercent ?? -1e9) > (a.meanExcessReturnPercent ?? -1e9) ? b : a));
  const totalPicks = usable.reduce((a, s) => a + s.sampleSize, 0);
  const useful = usable.filter((s) => Number.isFinite(s.informationCoefficient) && s.informationCoefficient >= USEFUL_IC);

  return el('div', {},
    el('div.grid.kpis', {},
      kpi({ label: 'Scored Picks', value: num(totalPicks), sub: `across ${usable.length} engine/horizon combinations`, tone: 'neutral' }),
      kpi({ label: 'Best Excess Return', value: pct(best.meanExcessReturnPercent), raw: best.meanExcessReturnPercent, tone: 'auto', sub: `${humanLabel(best.source)} at ${best.horizonDays} days` }),
      kpi({ label: 'Combinations Beating Noise', value: `${useful.length} of ${usable.length}`, sub: `IC at or above +${USEFUL_IC.toFixed(2)}`, tone: useful.length > 0 ? 'positive' : 'warning' })));
}

// ------------------------------------------------------------ the big table

function statsTable() {
  const rows = stats.filter((s) => s.sampleSize > 0);
  if (rows.length === 0) {
    return empty('No measured outcomes yet',
      'Picks are scored on their 30, 90, 180 and 365-day anniversaries. Until the first batch matures there is nothing to report.');
  }

  /** Withholds a figure when the sample is too small to support it. */
  const guarded = (s, render) => (s.sampleSize < MIN_SAMPLE
    ? unmeasured(`Only ${s.sampleSize} pick${s.sampleSize === 1 ? '' : 's'} — too few to draw a conclusion from`)
    : render());

  return table([
    { key: 'source', label: 'Engine', render: (s) => humanLabel(s.source) },
    { key: 'horizonDays', label: 'Held For', align: 'r', render: (s) => `${s.horizonDays} days` },
    { key: 'sampleSize', label: 'Picks', align: 'r', render: (s) => num(s.sampleSize) },
    {
      key: 'hitRatePercent',
      label: 'Hit Rate',
      align: 'r',
      render: (s) => guarded(s, () => pct(s.hitRatePercent, { signed: false })),
    },
    {
      key: 'meanReturnPercent',
      label: 'Avg Return',
      align: 'r',
      render: (s) => guarded(s, () => el('span', { class: sign(s.meanReturnPercent) }, pct(s.meanReturnPercent))),
    },
    {
      key: 'meanExcessReturnPercent',
      label: 'vs Nifty',
      align: 'r',
      render: (s) => guarded(s, () => el('span', { class: sign(s.meanExcessReturnPercent) }, pct(s.meanExcessReturnPercent))),
    },
    {
      key: 'informationCoefficient',
      label: 'IC',
      align: 'r',
      render: (s) => guarded(s, () => (missing(s.informationCoefficient)
        ? unmeasured('Could not be computed — scores or returns had no variation')
        : el('span', { style: s.informationCoefficient >= USEFUL_IC ? 'font-weight:600;color:var(--profit)' : '' }, corr(s.informationCoefficient)))),
    },
    {
      key: 'targetHitRatePercent',
      label: 'Target Reached',
      align: 'r',
      render: (s) => (missing(s.targetHitRatePercent)
        ? unmeasured('This engine does not set a target price on its picks')
        : `${pct(s.targetHitRatePercent, { signed: false })} of ${num(s.picksWithTarget)}`),
    },
    {
      key: 'stopLossHitRatePercent',
      label: 'Stop Hit',
      align: 'r',
      render: (s) => (missing(s.stopLossHitRatePercent)
        ? unmeasured('This engine does not set a stop-loss on its picks')
        : `${pct(s.stopLossHitRatePercent, { signed: false })} of ${num(s.picksWithStopLoss)}`),
    },
  ], rows, { sortKey: 'sampleSize' });
}

// -------------------------------------------------------------------- charts

function excessChart() {
  const usable = stats.filter((s) => s.sampleSize >= MIN_SAMPLE && Number.isFinite(s.meanExcessReturnPercent));
  if (usable.length === 0) {
    return empty('Not enough data to chart', `Needs at least ${MIN_SAMPLE} measured picks in a combination.`);
  }
  const items = usable
    .sort((a, b) => b.meanExcessReturnPercent - a.meanExcessReturnPercent)
    .map((s) => ({
      label: `${humanLabel(s.source)} · ${s.horizonDays}d`,
      value: s.meanExcessReturnPercent,
      note: `${s.sampleSize} picks`,
    }));
  return card(barChart(items, { width: 760, labelWidth: 210, format: (v) => pct(v) }));
}

function icChart() {
  const usable = stats.filter((s) => s.sampleSize >= MIN_SAMPLE && Number.isFinite(s.informationCoefficient));
  if (usable.length === 0) {
    return empty('Not enough data to chart', 'IC needs a reasonable sample and some variation in both scores and returns.');
  }
  const items = usable
    .sort((a, b) => b.informationCoefficient - a.informationCoefficient)
    .map((s) => ({
      label: `${humanLabel(s.source)} · ${s.horizonDays}d`,
      value: s.informationCoefficient,
      note: `${s.sampleSize} picks`,
    }));
  return card(barChart(items, {
    width: 760, labelWidth: 210, format: (v) => corr(v), reference: USEFUL_IC,
  }));
}

// ----------------------------------------------------- per-dimension IC (lazy)

/**
 * Click-only: /api/accuracy/dimension-ic fetches realized prices from the broker despite
 * being a GET, so page-loading it would violate the 2-second budget and hammer Kite.
 */
function dimensionIcPanel() {
  const host = el('div', {});
  const controls = el('div.row.wrap', { style: 'gap:8px' });

  let source = 'MULTIBAGGER';
  let horizon = 90;

  const select = (label, options, current, onPick) => {
    const wrap = el('span.row', { style: 'gap:5px' }, el('span.muted', { style: 'font-size:12.5px' }, label));
    for (const [value, text] of options) {
      const chip = el('button.chip', { 'aria-pressed': String(current() === value) }, text);
      chip.addEventListener('click', () => {
        onPick(value);
        [...controls.querySelectorAll('.chip')].forEach((c) => {
          const owner = c.dataset.group;
          if (owner === label) c.setAttribute('aria-pressed', String(c.textContent === text));
        });
      });
      chip.dataset.group = label;
      wrap.append(chip);
    }
    return wrap;
  };

  controls.append(
    // SECTOR_REVERSAL is retired (SPEC 39.2) but its rows are kept as the evidence for the
    // removal, so the option stays and says so rather than looking like a live engine.
    select('Engine:', SOURCES.map((s) => [s, s === 'SECTOR_REVERSAL' ? 'Sector Reversal (retired)' : humanLabel(s)]), () => source, (v) => { source = v; }),
    select('Horizon:', [[30, '30d'], [90, '90d'], [180, '180d']], () => horizon, (v) => { horizon = v; }));

  const btn = el('button.action', {}, 'Work out which dimensions are working');
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = 'Fetching prices from your broker…';
    host.replaceChildren(el('div.skeleton', { style: 'height:180px' }));
    try {
      const res = await getOnDemand(`/api/accuracy/dimension-ic?horizon=${horizon}&source=${source}`);
      host.replaceChildren(dimensionIcResult(Array.isArray(res) ? res : []));
    } catch (e) {
      host.replaceChildren(alert({ severity: 'WARNING', title: 'Could not compute this', message: String(e.message || e) }));
    } finally {
      btn.disabled = false;
      btn.textContent = 'Recalculate';
    }
  });

  return el('div', {},
    costNote('This one is slow: it fetches historical prices from your broker and can take up to a minute. It only runs when you press the button.'),
    controls,
    el('div', { style: 'margin-top:10px' }, btn),
    el('div', { style: 'margin-top:14px' }, host));
}

function dimensionIcResult(rows) {
  const usable = rows.filter((r) => Number.isFinite(r.informationCoefficient));
  if (usable.length === 0) {
    const reasons = [...new Set(rows.map((r) => r.icUnavailableReason).filter(Boolean))];
    return empty('No usable results for this combination',
      reasons.length
        ? `Reported reasons: ${reasons.map(humanLabel).join(', ')}. "Constant score" means every pick scored the same on that dimension, so there is nothing to correlate.`
        : 'No dimensions could be scored for this engine and horizon yet.');
  }

  const items = usable
    .sort((a, b) => b.informationCoefficient - a.informationCoefficient)
    .map((r) => ({
      label: humanLabel(r.dimension),
      value: r.informationCoefficient,
      note: `${r.sampleSize} picks`,
    }));

  const skipped = rows.filter((r) => !Number.isFinite(r.informationCoefficient));

  return el('div', {},
    card(barChart(items, { width: 760, labelWidth: 190, format: (v) => corr(v), reference: USEFUL_IC })),
    el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
      'Bars past the dashed line are dimensions that genuinely helped pick winners. Bars left of zero actively worked against you.'),
    skipped.length
      ? el('div.muted', { style: 'font-size:12.5px;margin-top:6px' },
        `Not computed: ${skipped.map((r) => `${humanLabel(r.dimension)} (${humanLabel(r.icUnavailableReason)})`).join(', ')}`)
      : null);
}

// ------------------------------------------------------- signal coverage (SPEC 38.2)

/**
 * Coverage sits directly beside the per-dimension IC because the two are only meaningful
 * together: an IC near zero on a signal measured for 30% of the universe says nothing about
 * the signal, and the app has twice spent months reading exactly that as "weak signal"
 * (Institutional Interest scored every stock 40 for three months; monthly RSI returned a
 * constant 50).
 */

/**
 * The seven weighted dimensions — a gap in one of these is far more serious than in a lens.
 *
 * SectorTailwind was the eighth until 2026-09-03 (SPEC 39.2). It is deliberately NOT here: a
 * historical coverage row for it would otherwise be flagged as a serious gap in a weighted
 * dimension, when in truth it now carries no weight at all.
 */
const WEIGHTED = new Set(['TechnicalMomentum', 'VolumeAccumulation', 'RelativeStrength',
  'PriceStructure', 'Valuation', 'InstitutionalInterest', 'FinancialQuality']);

const ACRONYMS = { Roce: 'ROCE', Roe: 'ROE', Roa: 'ROA', Rsi: 'RSI', Dcf: 'DCF', Ocf: 'OCF', Peg: 'PEG', Pe: 'PE', Adv20d: 'ADV20' };

/** "InsiderPulseScore" -> "Insider Pulse Score"; humanLabel() would give "Insiderpulsescore". */
function signalLabel(name) {
  return String(name || '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(' ')
    .map((w) => ACRONYMS[w] || w)
    .join(' ');
}

function coverageHeadline() {
  const r = coverage[0];
  if (!r) return null;

  const zero = coverage.filter((c) => c.coveragePercent === 0);
  const collapsed = coverage.filter((c) => c.collapsed === true);
  // Named once each: a signal already called out as missing everywhere or as collapsed does
  // not also need listing as thin.
  const named = new Set([...zero, ...collapsed].map((c) => c.signalName));
  const thin = coverage.filter((c) => !named.has(c.signalName)
    && Number.isFinite(c.coveragePercent) && c.coveragePercent > 0 && c.coveragePercent <= 50);

  const problems = [];
  if (zero.length) {
    problems.push(`${zero.map((c) => signalLabel(c.signalName)).join(', ')} — measured for no stock at all, so nothing downstream can say anything about ${zero.length === 1 ? 'it' : 'them'}.`);
  }
  if (collapsed.length) {
    problems.push(`${collapsed.map((c) => signalLabel(c.signalName)).join(', ')} — scored nearly every stock the same, so ${collapsed.length === 1 ? 'it is' : 'they are'} not separating good from bad whatever the weighting says.`);
  }
  if (thin.length) {
    problems.push(`Thinly covered: ${thin.map((c) => `${signalLabel(c.signalName)} (${c.coveragePercent.toFixed(0)}%)`).join(', ')}.`);
  }

  return el('div', {},
    el('div.grid.kpis', {},
      kpi({ label: 'Stocks Scored', value: num(r.screenedCount), sub: `of ${num(r.universeSize)} in the universe`, tone: 'neutral' }),
      kpi({ label: 'Rejected on Quality', value: num(r.qualityRejectedCount), sub: 'scored, then dropped by the size and quality filters — a decision, not a blind spot', tone: 'neutral' }),
      kpi({ label: 'Could Not Be Scored', value: num(r.failedCount), sub: 'genuine blind spots in the run', tone: r.failedCount > 0 ? 'warning' : 'positive' }),
      kpi({ label: 'Signals Checked', value: num(coverage.length), sub: `from the run of ${shortDate(r.screeningDate)}`, tone: 'neutral' })),
    problems.length
      ? alert({
        severity: collapsed.length || zero.length ? 'WARNING' : 'INFO',
        title: 'Signals that cannot support a conclusion',
        message: problems.join(' '),
        meta: 'Read any score for these as provisional. A signal nobody can measure is not evidence about that signal — it is a data gap to fix first.',
      })
      : alert({ severity: 'OPPORTUNITY', title: 'Every signal measured on a usable share of the universe', message: 'No signal is missing everywhere, and none has collapsed to a single value.' }));
}

function coverageTable() {
  if (coverage.length === 0) {
    return empty('No coverage recorded yet',
      'The coverage vector is written at the end of each full screening run (2:00 PM on weekdays, 8:00 AM Saturday). Nothing has been recorded yet.');
  }

  /** Coverage reads green above 90%, red at or below 50% — the level at which a signal stops being usable. */
  const coverageCell = (c) => {
    if (missing(c.coveragePercent)) {
      return unmeasured('This signal did not apply to a single stock in the run, so there is nothing to take a percentage of');
    }
    const tone = c.coveragePercent >= 90 ? 'pos' : c.coveragePercent <= 50 ? 'neg' : '';
    return el('span' + (tone ? '.' + tone : ''), {}, pct(c.coveragePercent, { signed: false, digits: 0 }));
  };

  /**
   * A collapsed spread is only meaningful on the engine's 0-100 scale. Debt-to-equity has a
   * standard deviation of 0.5 across the whole universe and that is a wide spread, so ratios
   * report their spread and get no verdict rather than a false alarm.
   */
  const statusCell = (c) => {
    if (c.collapsed === true) return badge('COLLAPSED', { type: 'danger', label: 'Not separating' });
    if (c.collapsed === false) return badge('SEPARATING', { type: 'success', label: 'Separating' });
    if (!missing(c.stdDev)) return badge('NA', { type: 'neutral', label: 'Not a 0-100 scale' });
    return unmeasured('Too few measured values in this run to judge the spread');
  };

  return table([
    {
      key: 'signalName',
      label: 'Signal',
      render: (c) => el('span', {},
        signalLabel(c.signalName),
        // Rendered with an explicit separator rather than relying on a margin alone: at small
        // sizes "Sector Tailwindweighted" is what the reader actually sees.
        WEIGHTED.has(c.signalName) ? el('span.muted', { style: 'font-size:11.5px;margin-left:6px' }, '· weighted') : null),
    },
    { key: 'coveragePercent', label: 'Measured For', align: 'r', render: coverageCell },
    { key: 'measured', label: 'Stocks', align: 'r', render: (c) => num(c.measured) },
    {
      key: 'notApplicable',
      label: 'Does Not Apply',
      align: 'r',
      render: (c) => (c.notApplicable > 0
        ? el('span', { title: 'Banks and other financials, where this measure is suppressed on purpose' }, num(c.notApplicable))
        : el('span.muted', {}, '—')),
    },
    {
      key: 'notMeasured',
      label: 'Missing',
      align: 'r',
      render: (c) => (c.notMeasured > 0 ? el('span' + (c.notMeasured > c.measured ? '.neg' : ''), {}, num(c.notMeasured)) : el('span.muted', {}, '—')),
    },
    { key: 'meanValue', label: 'Average', align: 'r', render: (c) => (missing(c.meanValue) ? unmeasured('Not enough measured values to average') : num(c.meanValue, 1)) },
    { key: 'stdDev', label: 'Spread', align: 'r', render: (c) => (missing(c.stdDev) ? unmeasured('Fewer than 10 measured values, so the spread would be noise') : num(c.stdDev, 1)) },
    { key: 'status', label: 'Verdict', sortable: false, render: statusCell },
  ], coverage, { sortKey: 'coveragePercent', sortDir: 'asc', emptyMessage: 'No signals recorded for this run.' });
}

// ------------------------------------------------------------- live signals

// --------------------------------------------------------------------- boot

async function boot() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading track record…'), skeleton(3)));
  await initChrome();

  const [statsRes, coverageRes, analystRes, analystRecentRes, overlapRes] = await Promise.all([
    get('/api/accuracy/summary', { fallback: [] }).catch(() => ({ data: [] })),
    // DB-only: one table, one date (SPEC 38.4). Verified by hand — a page-load get() is
    // ungated, so only the allowlist on getOnDemand() would have caught a slow endpoint here.
    get('/api/accuracy/coverage', { fallback: [] }).catch(() => ({ data: [] })),
    // Analyst target ledger (SPEC 49.8). Both DB-only — verified by hand, because a page-load
    // get() is ungated and only getOnDemand() carries the allowlist (Gotcha 39).
    get('/api/analyst/track-record', { fallback: null }).catch(() => ({ data: null })),
    get('/api/analyst/recent?days=365', { fallback: null }).catch(() => ({ data: null })),
    get('/api/analyst/overlap', { fallback: null }).catch(() => ({ data: null })),
  ]);

  stats = Array.isArray(statsRes.data) ? statsRes.data : [];
  // This endpoint hands back percentages as pre-formatted STRINGS, so they must be coerced
  // before any comparison or chart maths.
  coverage = Array.isArray(coverageRes.data) ? coverageRes.data : [];
  analystRecord = (analystRes && analystRes.data) || null;
  const recentData = (analystRecentRes && analystRecentRes.data) || null;
  analystRecent = (recentData && Array.isArray(recentData.targets)) ? recentData.targets : [];
  // The pill must count the rows actually drawn, and the blurb must name the rest. A count that
  // describes a different list from the one on screen is B-098 exactly.
  analystRecentTotal = (recentData && Number.isFinite(recentData.total)) ? recentData.total : analystRecent.length;
  analystRecentDays = (recentData && Number.isFinite(recentData.days)) ? recentData.days : 365;
  overlap = (overlapRes && overlapRes.data) || null;

  const nodes = [];

  nodes.push(section('Is the app any good at picking stocks?',
    'This is the app marking its own homework. Every time a scoring engine recommends a stock, the pick is recorded; on its 30, 90, 180 and 365-day anniversary the actual return is measured and compared against the Nifty 50. What matters is excess return — beating the index, not just going up.',
    headline()));

  nodes.push(section('Full results by engine and holding period',
    'One row per engine and holding period. "Hit rate" is the share of picks that made money; "vs Nifty" is how much better or worse than simply buying the index; "IC" measures whether a higher score genuinely led to a better return. Combinations with fewer than 10 measured picks show no numbers at all — a hit rate from a handful of picks is noise dressed up as evidence.',
    statsTable()));

  nodes.push(section('Beating the index, or not',
    'Average excess return for each engine and holding period. Anything left of zero underperformed simply buying the Nifty 50 — which is the honest benchmark for whether all this analysis is worth doing.',
    excessChart()));

  nodes.push(section('Is a higher score actually a better stock?',
    'IC measures whether the app’s scores line up with real returns. Above +0.10 — the dashed line — is the conventional bar for a genuinely useful signal. Near zero means the score and the outcome are unrelated.',
    icChart()));

  nodes.push(section('Which parts of the score are earning their keep?',
    'The composite blends seven dimensions. This breaks it apart and scores each one separately, so a dimension that adds nothing (or actively misleads) becomes visible instead of hiding inside the average.',
    dimensionIcPanel()));

  if (coverage.length > 0) {
    nodes.push(section('Could the app even measure these signals?',
      'Read this beside the panel above. A dimension can score near zero for two completely different reasons — it genuinely does not predict returns, or it was never calculated for most of the universe — and they look identical in an IC number. This is the second reason, made visible. "Measured for" is the share of screened stocks the signal produced a real value for; anything at or below half is a signal the app is mostly blind on. "Does not apply" is separated out because a bank with no return-on-capital figure is not a gap — that measure is suppressed for banks on purpose.',
      coverageHeadline(),
      el('div', { style: 'margin-top:14px' }, coverageTable())));
  }


  // The analysts' record (SPEC 49). Same question as everything above it, asked of somebody
  // else, on the same yardstick. The caveat block is mandatory rather than decorative: the
  // sample is the desks that publish into this feed, not a census of Indian equity research.
  nodes.push(section('And how have the analysts done?',
    'Brokerages publish price targets on the stocks they cover. This app records each published target — which firm, what price, what date — and then checks what the share price actually did: whether it ever reached the target, how long that took, and how the stock fared against the Nifty 50 over exactly the same dates. That last part is the point, because a target reached during a market-wide rally is not skill. None of it changes any score in this app — it is here to be scored, not followed.',
    caveatBlock(analystRecord && analystRecord.caveat),
    analystCoverage(),
    el('div', { style: 'margin-top:14px' }, analystHouses())));

  if (analystRecent.length > 0) {
  // Where our own screening and the brokerages point different ways (SPEC 49.12). It sits under
  // the houses' record because it is the same subject, and above the raw ledger because a reader
  // wants the join before the rows.
  if (overlap && overlap.screenedStocks > 0) {
    const good = overlap.good || [];
    const above = overlap.abovePublishedTargets || [];
    const none = overlap.goodWithoutTarget || [];
    const agreed = overlap.strongestAgreement || [];

    nodes.push(section('Do the analysts like the same stocks we do?',
      'Our screening score and the brokerages’ live price targets are two independent readings of the same companies. This compares them. It is not a view on whether any target will be reached — the two mostly disagree for a mechanical reason, and the notes below say what that reason is.',
      overlapKpis(),
      el('div', { style: 'margin-top:14px' }, overlapActions()),
      el('div', { style: 'margin-top:14px' }, overlapNotes()),
      el('div', { style: 'margin-top:14px' }, overlapBands())));

    if (agreed.length > 0) {
      nodes.push(collapse(withCount(section('Both sides rate these',
        `This app scores the business highly and at least ${overlap.minHousesForAgreement || 3} separate firms are quoting a live target on it. It is the shortest list here and the one carrying the most evidence, because two unrelated methods arrived at the same company. Sorted by how many firms are quoting — that column, not the claimed upside, is what makes one row stronger than another. A negative upside here is not a row to skip: it means several firms follow the company and still think the price has run ahead of it.`,
        table(overlapColumns(), agreed, { sortKey: 'houses', sortDir: 'desc' })), agreed.length),
        { key: 'accuracy:overlap-agreed', open: true }));
    }

    if (good.length > 0) {
      nodes.push(collapse(withCount(section('Our good stocks that a firm is quoting',
        'The full list — stocks this app scores highly that also carry a target no brokerage has yet revised, with the best-evidenced of them pulled out above. Our own red-flag column sits beside the target, because a bullish call on a stock our screen has flagged is the disagreement worth seeing. Check the Firms column before the claimed upside: a large number from a single desk is one opinion published once.',
        table(overlapColumns(), good, { sortKey: 'composite', sortDir: 'desc' })), good.length),
        { key: 'accuracy:overlap-good', open: false }));
    }

    if (above.length > 0) {
      nodes.push(collapse(withCount(section('Already past every target on them',
        'The share price has passed the highest target the quoted firms published and have not revised. That is the sharpest form the disagreement takes — our score is high because the stock has performed, and the sell-side thinks it has gone far enough. A reason to look harder before adding, not an instruction to sell.',
        table(overlapColumns(), above, { sortKey: 'upsideToMedianPct', sortDir: 'asc' })), above.length),
        { key: 'accuracy:overlap-above', open: true }));
    }

    if (none.length > 0) {
      nodes.push(collapse(withCount(section('Our good stocks nobody is quoting',
        'No brokerage has published a live target on these into the feed this app reads. That is not evidence nobody follows them — and a good business nobody quotes is the kind of thing the under-the-radar lens exists to find.',
        table(overlapColumns(), none, { sortKey: 'composite', sortDir: 'desc' })), none.length),
        { key: 'accuracy:overlap-none', open: false }));
    }
  }

    const truncated = analystRecentTotal > analystRecent.length;
    const scope = truncated
      ? `The ${analystRecent.length} most recent of ${num(analystRecentTotal)} calls recorded in the last ${analystRecentDays} days — the rest are on each stock's own page.`
      : 'One row per recorded call, newest first.';
    nodes.push(collapse(withCount(section(truncated ? 'The most recent targets' : 'Every target on record',
      `${scope} "Still running" means the horizon has not run out, so it counts as neither a hit nor a miss. "Revised since" means the same firm published a new target before this one was due — not a miss, because they withdrew it, but counted in the revision column above.`,
      analystRecentTable()), analystRecent.length), { key: 'accuracy:analyst-targets', open: false }));
  }


  mount(view, nodes);
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '', empty('Could not load the track record', String(err && err.message ? err.message : err))));
});
