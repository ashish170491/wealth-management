/**
 * Events — what is happening outside your companies, and which of them it touches (SPEC 48).
 *
 * The honest shape of this feature, and the reason it is not a news feed.
 *
 * A sentiment reading on a headline cannot tell you that "the rupee fell" is bad for the country
 * and good for TCS. Only a map of which businesses are exposed to which quantity, and in which
 * direction, can do that — so the app keeps two things strictly apart. An event ledger records
 * WHAT HAPPENED (a factor, a direction, a size, a date) and is never allowed to name a company.
 * An exposure map, curated and readable on this page, records WHICH BUSINESSES a factor helps or
 * hurts and through what channel. The reading you see is a join of the two, and nothing else.
 *
 * Four things about this page are deliberate.
 *
 * Most days the right answer is "nothing here concerns you", and the page says so in as many
 * words rather than going quiet. A screen that only appears when it has something alarming
 * trains the reader to treat its presence as a warning.
 *
 * Nothing here changes a score. Not the composite, not the buy-timing verdict, not a
 * recommendation. Every reading is filed for measurement at 180 and 365 days and is worth zero
 * points until it has earned otherwise.
 *
 * "Not measured" and "nothing applies" are different answers and never render alike — the first
 * is a gap in the app's own table, the second is a finding.
 *
 * And the one control that leaves the machine asks first (Gotcha 118).
 *
 * Page-load requests, all verified DB- or classpath-only by hand — a page-load get() is ungated
 * and only getOnDemand() has the allowlist (Gotcha 39):
 *   GET /api/macro/exposure/portfolio   holdings + the latest events, no broker, no NSE
 *   GET /api/macro/events               the event ledger
 *   GET /api/macro/calendar             a classpath CSV plus one holdings read
 *   GET /api/macro/map                  the classpath rule table
 *   GET /api/macro/status               one timestamp and the configured reader
 */

import { get, post, OfflineError } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters, sectorOptions } from './filters.js';
import {
  num, displaySymbol, stockHref, shortDate, dateTimeIst, humanLabel, missing,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, badge, table, alert, withCount, collapse,
  unmeasured,
} from './ui.js';
import {
  MACRO, STRENGTH, MAGNITUDE, DIRECTION, KIND, factorLabel,
  macroExposureCol, macroFilterGroup, macroCoverageLine,
} from './macro-cells.js';

const view = document.getElementById('view');

/** How long the reader may look back on the events table. */
const WINDOWS = [7, 14, 30, 90];

let eventDays = 14;
let calendarDays = 30;
let factorShown = '';

// ------------------------------------------------------------------ portfolio

const PORTFOLIO_FILTERS = chipFilters([
  macroFilterGroup(),
  {
    label: 'Sector:',
    key: 'sector',
    ownLine: true,
    options: (rows) => sectorOptions(rows, { key: 'sector', label: humanLabel }),
  },
  { search: true, placeholder: 'Search a holding…' },
], () => render());

function portfolioSection(data) {
  const explain = 'An event outside any one company — an interest-rate decision, a tariff, the price '
    + 'of oil, the rupee, the monsoon — can change how easy or hard the next few quarters are for a '
    + 'whole industry. This is which of your holdings that applies to right now. It is background '
    + 'for reading the next set of results, not a reason to do anything today, and it changes none '
    + 'of your scores.';

  if (!data) {
    return section('Your portfolio right now', explain,
      card(empty('Not available', 'Your holdings’ exposure could not be loaded.')));
  }

  const rows = data.holdings || [];
  const days = data.windowDays || 14;

  const kpis = el('div.grid.kpis', {},
    kpi({
      label: 'Facing a headwind',
      value: num(data.headwinds),
      sub: 'harder quarters ahead',
      tone: data.headwinds ? 'negative' : 'neutral',
    }),
    kpi({
      label: 'With a tailwind',
      value: num(data.tailwinds),
      sub: 'easier quarters ahead',
      tone: data.tailwinds ? 'positive' : 'neutral',
    }),
    kpi({
      label: 'Events in the window',
      value: num(data.eventsInWindow),
      sub: `last ${days} days`,
      tone: 'neutral',
    }),
    // Measured and not-measured are shown as one fraction rather than as two tiles, so the
    // denominator cannot get separated from the number that depends on it.
    kpi({
      label: 'Holdings with a rule',
      value: `${num(data.holdingsMeasured)} of ${num(rows.length)}`,
      sub: data.holdingsNotMeasured ? `${data.holdingsNotMeasured} not in the map yet` : 'all covered',
      tone: 'neutral',
    }));

  // The sentence that IS the feature working. Stated plainly, and only when it is true:
  // events were recorded and none of them reaches the portfolio.
  const quiet = data.nothingTouchesPortfolio
    ? alert({
      severity: 'INFO',
      title: 'Nothing in the news reaches your portfolio',
      message: data.note + ' That is the usual result, and it is worth reading as one: most of '
        + 'what moves the news does not move the businesses you have chosen.',
    })
    : null;

  const cols = [
    {
      key: 'symbol',
      label: 'Stock',
      render: (r) => el('a', { href: stockHref(r.symbol), title: r.symbol }, displaySymbol(r.symbol)),
    },
    {
      key: 'sector',
      label: 'Sector',
      render: (r) => (r.sector ? el('span', {}, humanLabel(r.sector)) : el('span.faint', {}, 'not classified')),
    },
    macroExposureCol(),
    {
      key: 'macroExposureEvents',
      label: 'Events',
      align: 'r',
      render: (r) => (missing(r.macroExposureEvents)
        ? el('span.faint', {}, '—')
        : el('span.num', {}, num(r.macroExposureEvents))),
    },
    {
      key: 'reason',
      label: 'Why',
      sortable: false,
      value: (r) => (r.macroExposureReasons || [])[0] || '',
      render: (r) => {
        const reasons = r.macroExposureReasons || [];
        if (!reasons.length) return el('span.faint', {}, '—');
        const wrap = el('div', { style: 'max-width:420px' },
          el('div', { style: 'font-size:12.5px' }, reasons[0]));
        for (const extra of reasons.slice(1)) {
          wrap.append(el('div.faint', { style: 'font-size:11.5px;margin-top:2px' }, extra));
        }
        return wrap;
      },
    },
  ];

  const shown = PORTFOLIO_FILTERS.apply(rows);
  const body = rows.length
    // filter:false — this table's search lives in the chip bar with the chips it works alongside,
    // rather than as a second box under them (Gotcha 85).
    ? el('div', {}, PORTFOLIO_FILTERS.bar(rows, shown.length, 'holdings'),
      table(cols, shown, { sortKey: 'macroExposure', sortDir: 'asc', filter: false }))
    : empty('No holdings on record', 'Once the broker sync has run, your holdings appear here.');

  return withCount(
    section('Your portfolio right now', explain,
      card(kpis, quiet, body, macroCoverageLine(rows, days))),
    rows.length,
  );
}

// ------------------------------------------------------------------ events

const EVENT_FILTERS = chipFilters([
  {
    label: 'Expected?',
    key: 'kind',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'SCHEDULED', text: 'On the calendar', test: (r) => r.kind === 'SCHEDULED',
        title: 'A policy meeting, a budget, a data release — something the market knew was coming.',
      },
      {
        value: 'SURPRISE', text: 'Unexpected', test: (r) => r.kind === 'SURPRISE',
        title: 'Nobody had this dated in advance.',
      },
    ],
  },
  {
    label: 'Size:',
    key: 'magnitude',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'LARGE', text: 'Large', test: (r) => r.magnitude === 'LARGE' },
      { value: 'MODERATE', text: 'Moderate', test: (r) => r.magnitude === 'MODERATE' },
      { value: 'SMALL', text: 'Small', test: (r) => r.magnitude === 'SMALL' },
    ],
  },
  {
    key: 'mine',
    toggle: true,
    text: 'Touches something I own or watch',
    title: 'Only events the exposure map connects to a stock in your portfolio or watchlist.',
    test: (r) => (r.exposedHoldings || []).length > 0 || (r.exposedWatchlist || []).length > 0,
  },
  {
    label: 'What moved:',
    key: 'factor',
    ownLine: true,
    options: (rows) => {
      const counts = new Map();
      for (const r of rows) {
        const key = r.factor || 'UNKNOWN';
        if (!counts.has(key)) counts.set(key, { n: 0, label: factorLabel(r) });
        counts.get(key).n += 1;
      }
      const named = [...counts.entries()].sort((a, b) => b[1].n - a[1].n);
      return [{ value: 'ALL', text: 'All' }, ...named.map(([k, v]) => ({
        value: k,
        text: `${v.label} (${v.n})`,
        test: (r) => (r.factor || 'UNKNOWN') === k,
      }))];
    },
  },
  { search: true, placeholder: 'Search these events…', fields: ['searchText'] },
], () => render());

/**
 * Flattens what a reader would actually type into one searchable field.
 *
 * The shared search skips nested objects, so the headline list and the exposed-stock arrays are
 * invisible to it. Typing a ticker you own and getting nothing back — when the row literally
 * names that ticker — is the kind of half-failure that is worse than no search at all.
 */
function withSearchText(e) {
  const stocks = [...(e.exposedHoldings || []), ...(e.exposedWatchlist || [])]
    .map(displaySymbol).join(' ');
  e.searchText = [
    factorLabel(e), e.summary, e.geography, e.kind, e.magnitude, e.extractor, stocks,
  ].filter(Boolean).join(' ');
  return e;
}

function directionCell(e) {
  const word = DIRECTION[e.direction];
  if (!word) return unmeasured('No direction recorded for this event');
  const size = MAGNITUDE[e.magnitude];
  const up = e.direction === 'UP';
  return el('div', {},
    el('span', { class: up ? 'pos' : 'neg', style: 'font-weight:600' },
      `${up ? '↑' : '↓'} ${word}`),
    size ? el('span.faint', { style: 'font-size:11.5px;margin-left:5px' }, size) : null);
}

function whatMovedCell(e) {
  return el('div', { style: 'max-width:330px' },
    el('div', { style: 'font-weight:600;font-size:13px' }, factorLabel(e)),
    e.summary ? el('div.faint', { style: 'font-size:11.5px;margin-top:2px' }, e.summary) : null,
    e.risesMeans
      ? el('div.faint', { style: 'font-size:11px;margin-top:2px' }, `“up” here means ${e.risesMeans}`)
      : null);
}

/**
 * Who read this event, and how sure it was.
 *
 * The keyword reader deliberately records no confidence at all, and that absence must render as
 * "not measured" rather than as a low number — a rule that cannot score its own certainty is a
 * different thing from one that scored itself badly (Gotcha 21).
 */
function readerCell(e) {
  const keyword = String(e.extractor || '').startsWith('KEYWORD');
  const label = keyword ? 'keyword rules' : (e.extractor || 'unknown');
  const node = el('div', {}, el('div', { style: 'font-size:12.5px' }, label));
  if (missing(e.confidence)) {
    node.append(el('div', { style: 'margin-top:2px' },
      unmeasured(keyword
        ? 'The keyword reader does not score its own confidence, so there is no number here.'
        : 'No confidence was recorded for this reading.')));
  } else {
    node.append(el('div.faint', { style: 'font-size:11.5px;margin-top:2px' },
      `${Math.round(e.confidence * 100)}% confident`));
  }
  return node;
}

function yoursCell(e) {
  const held = e.exposedHoldings || [];
  const watched = e.exposedWatchlist || [];
  if (!held.length && !watched.length) return el('span.faint', {}, 'none');

  const wrap = el('div', { style: 'max-width:230px' });
  if (held.length) {
    wrap.append(el('div', { style: 'font-size:12.5px' },
      el('b', {}, `${held.length} held: `), held.map(displaySymbol).join(', ')));
  }
  if (watched.length) {
    wrap.append(el('div.faint', { style: 'font-size:11.5px;margin-top:2px' },
      `${watched.length} watched: ${watched.map(displaySymbol).join(', ')}`));
  }
  return wrap;
}

function headlinesCell(e) {
  const links = e.headlines || [];
  if (!links.length) {
    return el('span.faint', {}, e.headlineCount ? `${e.headlineCount} headline(s)` : '—');
  }
  const d = el('details', {}, el('summary', { style: 'cursor:pointer;font-size:12.5px' },
    `${links.length} source${links.length === 1 ? '' : 's'}`));
  for (const h of links) {
    d.append(el('div', { style: 'font-size:11.5px;margin-top:3px;max-width:260px' },
      el('a', { href: h.url, target: '_blank', rel: 'noopener' }, h.url)));
  }
  return d;
}

/**
 * Marking an event as noise.
 *
 * Click-only, no confirmation: it is a database write that is trivially reversible in meaning
 * (the row is kept, flagged, and stops counting), and confirming every cheap action is what
 * teaches a reader to dismiss the confirmation on the expensive one.
 */
function dismissCell(e) {
  if (e.dismissed) return el('span.faint', { style: 'font-size:11.5px' }, 'dismissed');

  const btn = el('button.action.secondary.compact', {
    type: 'button',
    title: 'Records this as noise. It stops counting against your stocks and stays on the record — '
      + 'a ledger that quietly deleted what turned out to be wrong could not be judged later.',
  }, 'Not news');

  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = 'Dismissing…';
    try {
      await post(`/api/macro/events/dismiss?id=${encodeURIComponent(e.id)}`);
      render();
    } catch (err) {
      btn.disabled = false;
      btn.textContent = 'Not news';
      btn.title = String(err && err.message ? err.message : err);
    }
  });
  return btn;
}

function eventsSection(data) {
  const explain = 'Everything the app has read out of the news and turned into a dated event. Each row '
    + 'records only what moved and which way — never a company, and never a price call. Which of your '
    + 'stocks it reaches is worked out afterwards, from the exposure map at the bottom of this page, '
    + 'so you can check the reasoning rather than take it on trust.';

  if (!data) {
    return section('What has happened recently', explain,
      card(empty('Not available', 'The event ledger could not be loaded.')));
  }

  const all = (data.events || []).map(withSearchText);
  const live = all.filter((e) => !e.dismissed);

  const picker = el('div', {
    style: 'display:flex;gap:6px;align-items:center;margin-bottom:8px;font-size:12.5px;flex-wrap:wrap',
  },
  el('span.muted', {}, 'Look back'),
  ...WINDOWS.map((d) => el('button.action.secondary.compact', {
    onclick: () => { eventDays = d; render(); },
    style: d === eventDays ? 'font-weight:700;border-color:var(--navy)' : '',
  }, `${d} days`)));

  const cols = [
    {
      key: 'occurredAt',
      label: 'When',
      render: (e) => el('div', {},
        el('div', {}, shortDate(e.occurredAt)),
        e.geography ? el('div.faint', { style: 'font-size:11.5px' }, e.geography) : null),
    },
    { key: 'factor', label: 'What moved', sortable: false, render: whatMovedCell },
    { key: 'direction', label: 'Which way', render: directionCell },
    {
      key: 'kind',
      label: 'Expected?',
      render: (e) => (e.kind
        ? badge(e.kind, { type: e.kind === 'SCHEDULED' ? 'neutral' : 'warning', label: KIND[e.kind] || e.kind })
        : unmeasured('Not recorded as scheduled or unexpected')),
    },
    { key: 'extractor', label: 'Read by', sortable: false, render: readerCell },
    { key: 'yours', label: 'Your stocks', sortable: false, render: yoursCell },
    { key: 'headlines', label: 'Sources', sortable: false, render: headlinesCell },
    { key: 'dismiss', label: '', sortable: false, render: dismissCell },
  ];

  const shown = EVENT_FILTERS.apply(live);

  // The empty state is not an apology. Most days there genuinely is no macro event, and saying so
  // is the difference between a feature that is quiet and one that looks broken.
  const body = live.length
    ? el('div', {}, EVENT_FILTERS.bar(live, shown.length, 'events'),
      table(cols, shown, { sortKey: 'occurredAt', filter: false }))
    : empty('No event recorded in this window',
      'On most days that is the correct answer, not a failure. Widen the window above, or use '
      + '“Read the news feeds now” at the bottom of this page to look for new ones.');

  const dismissed = all.filter((e) => e.dismissed);
  const dismissedBlock = dismissed.length
    ? el('details', { style: 'margin-top:12px' },
      el('summary', { style: 'cursor:pointer;font-size:12.5px' },
        `${dismissed.length} event${dismissed.length === 1 ? '' : 's'} you marked as noise`),
      el('div', { style: 'margin-top:8px' },
        table(cols.slice(0, 5), dismissed, { sortKey: 'occurredAt', filter: false, layout: false })))
    : null;

  return withCount(
    section('What has happened recently', explain, card(picker, body, dismissedBlock)),
    live.length,
  );
}

// ------------------------------------------------------------------ calendar

function calendarSection(data) {
  const explain = 'The dated events ahead. The app does not know which way any of these will go and '
    + 'does not guess — there is no field in which it could record one. A date is here so nothing '
    + 'arrives as a surprise, not so it can be traded.';

  if (!data) {
    return section('What is coming up', explain,
      card(empty('Not available', 'The calendar could not be loaded.')));
  }

  const rows = data.entries || [];

  const picker = el('div', {
    style: 'display:flex;gap:6px;align-items:center;margin-bottom:8px;font-size:12.5px;flex-wrap:wrap',
  },
  el('span.muted', {}, 'Look ahead'),
  ...[30, 90, 180].map((d) => el('button.action.secondary.compact', {
    onclick: () => { calendarDays = d; render(); },
    style: d === calendarDays ? 'font-weight:700;border-color:var(--navy)' : '',
  }, `${d} days`)));

  const cols = [
    {
      key: 'date',
      label: 'When',
      render: (r) => el('div', {},
        el('div', { style: 'font-weight:600' }, shortDate(r.date)),
        el('div.faint', { style: 'font-size:11.5px' },
          r.daysAway === 0 ? 'today' : `in ${r.daysAway} day${r.daysAway === 1 ? '' : 's'}`)),
    },
    {
      key: 'label',
      label: 'What',
      render: (r) => el('div', { style: 'max-width:320px' },
        el('div', { style: 'font-size:13px' }, r.label),
        r.notes ? el('div.faint', { style: 'font-size:11.5px;margin-top:2px' }, r.notes) : null),
    },
    { key: 'factorLabel', label: 'Affects', render: (r) => el('span', {}, factorLabel(r)) },
    {
      key: 'geography',
      label: 'Where',
      render: (r) => (r.geography ? el('span', {}, r.geography) : el('span.faint', {}, '—')),
    },
    {
      key: 'sensitiveCount',
      label: 'Holdings it could touch',
      align: 'r',
      render: (r) => {
        if (!r.sensitiveCount) return el('span.faint', {}, 'none');
        const node = el('span.num', { style: 'font-weight:600' }, num(r.sensitiveCount));
        node.title = (r.sensitiveHoldings || []).map(displaySymbol).join(', ');
        return node;
      },
    },
  ];

  const body = rows.length
    ? table(cols, rows, { sortKey: 'date', sortDir: 'asc', filter: false })
    : empty('Nothing dated in this window',
      'The calendar is a file the app ships with. Widen the window above, or add dates to '
      + 'macro-calendar.csv.');

  const foot = el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    'The last column counts holdings the exposure map has a rule for on this kind of event — not a '
    + 'prediction that they will move. Nothing here is scored.');

  return withCount(section('What is coming up', explain, card(picker, body, foot)), rows.length);
}

// ------------------------------------------------------------------ the map

function mapSection(data) {
  const explain = 'The rule table the readings are built from, so you can check the reasoning rather '
    + 'than trust it. Each row says: when this quantity rises, this kind of business is helped or '
    + 'hurt, through this channel. It is hand-written, it is the part most likely to be wrong, and '
    + 'that is exactly why it is on screen.';

  if (!data) {
    return section('How the map works', explain,
      card(empty('Not available', 'The exposure map could not be loaded.')));
  }

  const factors = data.factors || [];
  const rules = data.rules || [];

  const picker = el('div', { style: 'display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px' },
    el('button.action.secondary.compact', {
      onclick: () => { factorShown = ''; render(); },
      style: factorShown ? '' : 'font-weight:700;border-color:var(--navy)',
    }, `Everything (${data.totalRules})`),
    ...factors.map((f) => el('button.action.secondary.compact', {
      onclick: () => { factorShown = f.factor; render(); },
      title: `Up means: ${f.risesMeans}`,
      style: factorShown === f.factor ? 'font-weight:700;border-color:var(--navy)' : '',
    }, `${f.label} (${f.rules})`)));

  const chosen = factors.find((f) => f.factor === factorShown);
  const meaning = chosen
    ? el('div.alert.info', {},
      el('div.alert-title', {}, chosen.label),
      el('div', {}, `“Up” means ${chosen.risesMeans}.`),
      chosen.plainEnglish ? el('div', { style: 'margin-top:5px' }, chosen.plainEnglish) : null)
    : null;

  const cols = [
    { key: 'factorLabel', label: 'When this', render: (r) => el('span', {}, factorLabel(r)) },
    {
      key: 'onRise',
      label: 'rises, the business is',
      render: (r) => {
        const spec = { HELPED: MACRO.TAILWIND, HURT: MACRO.HEADWIND, MIXED: MACRO.MIXED }[r.onRise];
        const label = { HELPED: 'helped', HURT: 'hurt', MIXED: 'both' }[r.onRise] || r.onRise;
        return badge(r.onRise, { type: (spec || MACRO.MIXED).type, label });
      },
    },
    {
      key: 'key',
      label: 'for',
      render: (r) => el('div', {},
        el('div', { style: 'font-size:13px' },
          r.scope === 'SYMBOL' ? displaySymbol(r.key) : humanLabel(r.key)),
        el('div.faint', { style: 'font-size:11px' },
          { SYMBOL: 'this company', INDUSTRY: 'this industry', SECTOR: 'this sector' }[r.scope] || r.scope)),
    },
    {
      key: 'strength',
      label: 'How much',
      render: (r) => el('span', {}, STRENGTH[r.strength] || humanLabel(r.strength)),
    },
    { key: 'channel', label: 'Through', render: (r) => el('span', {}, r.channel || '—') },
    {
      key: 'rationale',
      label: 'Why',
      sortable: false,
      render: (r) => el('div.muted', { style: 'font-size:12px;max-width:420px' }, r.rationale || ''),
    },
  ];

  const foot = el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `Version ${data.version} — ${data.totalRules} rules. The version is stamped on every reading `
    + 'the app files, so a reading made under an older map can never be confused with one made '
    + 'under this one. ',
    el('a', { href: 'guide.html#macro-exposure' }, 'What these words mean →'));

  // Folded by default: it is a 168-row reference, not something to read on every visit. The
  // count in the heading is what makes that safe (Gotcha 119).
  return collapse(
    withCount(section('How the map works', explain,
      card(picker, meaning, table(cols, rules, { sortKey: 'factorLabel', sortDir: 'asc' }), foot)),
    rules.length),
    { key: 'macro-map', open: false },
  );
}

// ------------------------------------------------------------------ ingest

/**
 * "Read the news feeds now" — the one control on this page that leaves the machine.
 *
 * It asks first (SPEC 27.14, Gotcha 118). Every other click on this dashboard is a database read;
 * this one fetches four RSS feeds and, when a language model is configured, pays that model to
 * read up to a hundred-odd headlines. So the reader is told what it does, what it costs, when it
 * is refused — and what it will NOT do — before it happens rather than after.
 *
 * The timeout is deliberately generous. An ingest that the server finishes while the browser has
 * already given up and said "that took too long" is the exact defect found on the IPO capture:
 * a client timeout shorter than the measured run is a bug, not a safety margin.
 */
const INGEST_TIMEOUT_MS = 180000;

function ingestPanel(status) {
  const host = el('div', { style: 'margin:4px 0 8px' });
  const line = el('div.muted', { style: 'font-size:12.5px;margin-top:7px' });

  const model = status && status.modelAvailable;
  const reader = (status && status.extractor) || 'keyword rules';

  const button = el('button.action.secondary.compact', {
    type: 'button',
    title: 'Fetches the news feeds live and reads them into events. Asks for confirmation first, '
      + 'and tells you what it costs.',
  }, 'Read the news feeds now');

  const idle = () => {
    button.disabled = false;
    button.textContent = 'Read the news feeds now';
    host.replaceChildren(button, line);
  };

  async function fire() {
    button.disabled = true;
    button.textContent = 'Reading…';
    host.replaceChildren(button, line);
    line.textContent = 'Fetching the feeds and reading them. Leave the page open — closing it does '
      + 'not stop the run, but you will not see the result.';
    try {
      const r = await post('/api/macro/ingest', undefined, INGEST_TIMEOUT_MS);
      line.textContent = `${r.note || 'Done.'} (${Math.round((r.durationMs || 0) / 1000)}s) Reloading…`;
      setTimeout(() => window.location.reload(), 1800);
    } catch (err) {
      // A 409 arrives carrying the guard's own sentence (Gotcha 60) — show it verbatim, because
      // explaining WHY it was refused is the entire reason that guard carries a reason.
      line.textContent = err instanceof OfflineError
        ? 'The app is not reachable right now.'
        : String(err && err.message ? err.message : err);
      idle();
    }
  }

  function confirmPanel() {
    const yes = el('button.action.compact', { type: 'button' }, 'Yes, read them now');
    const no = el('button.action.secondary.compact', { type: 'button' }, 'Cancel');
    yes.addEventListener('click', fire);
    no.addEventListener('click', () => { line.textContent = ''; idle(); });

    return el('div.alert.warning', {},
      el('div.alert-title', {}, 'This one goes out to the news feeds — shall I?'),
      el('div', {}, 'It fetches four public RSS feeds, then reads up to '
        + `${(status && status.maxHeadlinesPerIngest) || 120} headlines it has not seen before with `
        + `${model ? reader : 'the app’s own keyword rules'}. `
        + (model
          ? 'That costs roughly a rupee or two per run. '
          : 'No language model is configured, so this costs nothing but the time to fetch. ')
        + 'It writes any events it finds to the database and files the resulting readings so their '
        + 'accuracy can be checked in six months.'),
      el('div', { style: 'margin-top:7px' },
        el('b', {}, 'What it will not do: '),
        'it will not tell you to buy or sell anything, and it will not name a company. The reader '
        + 'extracts only what moved and which way; which of your stocks that reaches is decided '
        + 'afterwards by the map below, which you can read for yourself.'),
      el('div.muted', { style: 'margin-top:7px' },
        'It is refused between 09:40–10:15 and from '
        + `${(status && status.refuseFrom) || '14:00'} to the close on a trading day, because the `
        + 'FII/DII fetch, the 14:00 screening and the afternoon report jobs need the network. '
        + 'If it is refused you will be told why.'),
      el('div', { style: 'margin-top:11px;display:flex;gap:9px;flex-wrap:wrap' }, yes, no));
  }

  button.addEventListener('click', () => {
    line.textContent = '';
    host.replaceChildren(confirmPanel(), line);
  });

  host.append(button, line);
  return host;
}

function statusSection(status) {
  const explain = 'Nothing on this page happens on a timer. Reading the news costs a model call, and '
    + 'a standing cost nobody decided to pay is not a cost worth paying — so it runs when you press '
    + 'the button, and the app tells you what that will do first.';

  if (!status) {
    return section('Reading the news', explain,
      card(empty('Not available', 'The ingest status could not be loaded.')));
  }

  const rows = el('div', { style: 'font-size:13px' });
  const line = (label, value, note) => rows.append(el('div', {
    style: 'display:flex;gap:12px;padding:7px 0;border-bottom:1px solid var(--rule);flex-wrap:wrap',
  },
  el('span.muted', { style: 'min-width:180px' }, label),
  el('span', { style: 'font-weight:600' }, value),
  note ? el('span.muted', { style: 'flex:1;min-width:220px' }, note) : null));

  line('Last read', status.lastIngestAt ? dateTimeIst(status.lastIngestAt) : 'never',
    'Only ever when somebody pressed the button.');
  line('Reader', status.modelAvailable ? status.extractor : 'keyword rules',
    status.modelAvailable
      ? 'A language model extracts the events. It is never allowed to name a company.'
      : 'No language model is configured, so the app’s own keyword rules read the headlines. '
        + 'They record no confidence figure, and the events table says so rather than inventing one.');
  line('On a schedule', status.ingestScheduled ? 'yes' : 'no',
    status.ingestScheduled ? '' : 'Deliberately. Adding a cron needs a slot argued for in the spec.');
  line('Exposure map', `${status.exposureMapRules} rules · ${status.exposureMapVersion}`,
    'Stamped on every reading, so two different maps can never be mistaken for one.');
  line('Window', `${status.windowDays} days`,
    'How far back an event still counts against a business.');

  return section('Reading the news', explain,
    card(ingestPanel(status), rows,
      el('div.muted', { style: 'font-size:12.5px;margin-top:10px' }, status.cost || '')));
}

// ------------------------------------------------------------------ boot

async function render() {
  if (!view.firstChild) {
    view.replaceChildren(el('section.section', {},
      el('h2.section-title', {}, 'Loading events…'), skeleton(4)));
  }
  await initChrome();

  let portfolio = null;
  let events = null;
  let calendar = null;
  let map = null;
  let status = null;

  try {
    [portfolio, events, calendar, map, status] = await Promise.all([
      get('/api/macro/exposure/portfolio', { fallback: null }).then((r) => r.data).catch(() => null),
      get(`/api/macro/events?days=${eventDays}&includeDismissed=true`, { fallback: null })
        .then((r) => r.data).catch(() => null),
      get(`/api/macro/calendar?days=${calendarDays}`, { fallback: null }).then((r) => r.data).catch(() => null),
      get('/api/macro/map', { fallback: null }).then((r) => r.data).catch(() => null),
      get('/api/macro/status', { fallback: null }).then((r) => r.data).catch(() => null),
    ]);
  } catch (err) {
    mount(view, empty('Could not load events', String(err.message || err)));
    return;
  }

  const intro = el('div', { style: 'margin-bottom:6px' },
    el('h1', { style: 'font-size:23px;color:var(--navy)' }, 'Events'),
    el('div.muted', { style: 'font-size:13.5px' },
      'What is happening outside your companies, and which of them it actually touches.'));

  const banner = alert({
    severity: 'INFO',
    title: 'What this page will not do',
    message: 'It will not tell you the market is going up or down, and it will not turn a headline '
      + 'into a buy or a sell. A headwind is not a reason to sell and a tailwind is not a reason to '
      + 'buy — their use is in reading the next set of results correctly, because a good business '
      + 'having a hard quarter for a reason you can name is a very different thing from one whose '
      + 'thesis has broken. Nothing on this page changes any score in the app.',
  });

  mount(view,
    intro,
    banner,
    portfolioSection(portfolio),
    eventsSection(events),
    calendarSection(calendar),
    mapSection(map),
    statusSection(status));
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
render().then(() => registerRefresh(render)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '',
    empty('Could not load events', String(err && err.message ? err.message : err))));
});
