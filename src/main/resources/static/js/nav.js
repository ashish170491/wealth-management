/**
 * Shared page chrome: header, nav, freshness strip, refresh control (SPEC 27.12),
 * connection/shutdown banners.
 *
 * Lives in one file so the six HTML pages stay thin shells — this is what gives multi-page
 * the maintainability of an SPA without a router (SPEC section 27.3).
 *
 * The refresh control lives HERE, next to the timestamps, and not as a button inside each page,
 * because of what it is and is not. It RE-READS stored results; it never re-runs an analysis.
 * In this API a GET can send an email or start a thirty-minute scan (SPEC 27.4, Gotcha 17), the
 * Kite budget is one process-wide ~2.9 req/s gate that a manual job can starve the afternoon
 * schedulers out of (B-049), and the screening this page reads takes 30+ minutes. So "refresh"
 * can only honestly mean "ask the database again". Placing it beside the freshness strip is the
 * design saying so: the same click that re-reads the page re-reads the timestamps above it, and
 * then REPORTS WHETHER ANYTHING ACTUALLY MOVED. A button that promises fresh data and silently
 * returns the same rows teaches the investor to distrust the screen — the same reasoning api.js
 * already applies to the offline banner (B-086).
 */

import { get, onConnectionChange } from './api.js';
import { relative, daysAgo, dateTimeIst } from './format.js';
import { el } from './ui.js';

const PAGES = [
  { href: 'index.html', label: 'Overview' },
  { href: 'holdings.html', label: 'My Portfolio' },
  { href: 'screener.html', label: 'Screener' },
  { href: 'discovery.html', label: 'Discovery' },
  { href: 'accuracy.html', label: 'Track Record' },
  { href: 'market.html', label: 'Market' },
  { href: 'watchlist.html', label: 'Watchlist' },
  { href: 'ipo.html', label: 'IPOs' },
  { href: 'macro.html', label: 'Events' },
  { href: 'reports.html', label: 'Reports' },
  { href: 'health.html', label: 'Data Health' },
  { href: 'guide.html', label: 'Guide' },
];

/** Which freshness keys each page actually depends on — no point stamping the rest. */
const RELEVANT = {
  'index.html': ['holdingsSynced', 'holdingsHistory'],
  'holdings.html': ['holdingsSynced', 'holdingsAnalyzed', 'holdingsHistory', 'quarterlyResults'],
  'screener.html': ['multibaggerScores'],
  'discovery.html': ['multibaggerScores'],
  'accuracy.html': ['recommendationOutcomes', 'analystTargets'],
  'market.html': ['holdingsSynced'],
  'watchlist.html': ['watchlistAnalyzed', 'watchlistSnapshot', 'multibaggerScores'],
  'ipo.html': ['ipoIssues'],
  'macro.html': ['macroEvents', 'marketImpactNews'],
  'reports.html': ['holdingsSynced', 'holdingsAnalyzed'],
  // Deliberately empty: this page reports freshness for every table in full, against
  // each job's own cron. A shorter answer to the same question directly above it is
  // exactly the two-surfaces-one-question failure (Gotcha 85).
  'health.html': [],
};

const FRESHNESS_LABELS = {
  holdingsSynced: 'Prices',
  holdingsAnalyzed: 'Analysis',
  holdingsHistory: 'History',
  multibaggerScores: 'Scores',
  recommendationOutcomes: 'Outcomes',
  holdingClassification: 'Core tiers',
  watchlistAnalyzed: 'Watchlist',
  watchlistSnapshot: 'Watch history',
  ipoIssues: 'IPO feed',
  macroEvents: 'Events read',
  marketImpactNews: 'Headlines',
  analystTargets: 'Analyst targets',
  quarterlyResults: 'Results',
};

/** Past this many days a timestamp is called out in amber. */
const STALE_AFTER_DAYS = 1;

/**
 * Keys written only when the investor presses a button — never by a scheduled job.
 *
 * These must never be marked stale. Amber on this strip means "a job that should have run has
 * not", and that claim is simply false for a table nothing schedules: the macro event ledger is
 * as fresh as the last time somebody chose to read the news, and a fortnight-old stamp on it is
 * a fact about the reader's habits, not a fault. Marking it amber would train the eye to ignore
 * the colour on the keys where it does mean something (SPEC 44, the on-demand rule).
 */
const ON_DEMAND = new Set(['macroEvents']);

/**
 * Pages with no stored data behind them, which therefore get no refresh control.
 *
 * The guide is prose. A refresh button there would re-read nothing and report "nothing new"
 * for ever, which is worse than having no button: it is a control that exists to be ignored.
 */
const NO_REFRESH = new Set(['guide.html']);

/** How long a refresh result stays on screen before it stops being about this click. */
const STATUS_LINGER_MS = 12000;

let lastHealth = null;      // the newest health payload, so a refresh can diff against it
let reloadFn = null;        // the current page's own data reload, registered below
let refreshing = false;
let refreshNode = null;     // kept aside: paintFreshness replaces the strip's children
let statusTimer = null;

/**
 * Lets a page hand the refresh control its own reload.
 *
 * Register the page's existing entry function — {@code initChrome} is idempotent, so calling it
 * again only re-reads health. Register AFTER the first load completes (`boot().then(...)`), or a
 * click landing mid-load runs two loads at once.
 *
 * A page that registers nothing still gets a working button: it re-reads the freshness strip.
 * That is the honest floor, and it is right for a screen like Reports whose content is built on
 * demand rather than stored.
 */
export function registerRefresh(fn) {
  reloadFn = typeof fn === 'function' ? fn : null;
}

/**
 * The freshness stamps this page already fetched, for a page that needs to DATE its own figures.
 *
 * Overview renders "Today's Change" and "Today's biggest moves" from the holdings table, and at
 * 09:13 on a weekday those come from yesterday's 15:18 sync - the word "today" naming data that
 * is not today's (B-119's family: a figure quoted on a scale it was not measured on). Deriving
 * the label from `holdingsSynced` fixes that without a fifth request, and without a second
 * reader of health that could disagree with the strip about what the app last did.
 *
 * Null before `initChrome()` resolves, or when health could not be read - callers must degrade
 * to a wording that claims no date rather than assuming today.
 */
export function currentFreshness() {
  return (lastHealth && lastHealth.freshness) || null;
}

function currentPage() {
  const file = window.location.pathname.split('/').pop();
  return !file || file === '' ? 'index.html' : file;
}

function buildChrome(page) {
  const header = el('header.topbar', {},
    el('div.topbar-inner', {},
      el('h1', {}, 'Portfolio Dashboard'),
      el('div.tagline', {}, 'Your long-term holdings, scores and track record — all read-only.'),
      el('nav.nav', {}, PAGES.map((p) => el('a', {
        href: p.href,
        ...(p.href === page ? { 'aria-current': 'page' } : {}),
      }, p.label)))));

  const freshness = el('div.freshness', {}, el('div.freshness-inner#freshness-inner', {},
    el('span.muted', {}, 'Checking…')));

  const offline = el('div.banner.offline#banner-offline', {}, el('div.banner-inner#banner-offline-text'));
  const shutdown = el('div.banner.shutdown#banner-shutdown', {}, el('div.banner-inner#banner-shutdown-text'));

  return [header, freshness, offline, shutdown];
}

/**
 * The freshness keys a page is stamped with — and therefore the keys a refresh may claim moved.
 *
 * One resolution, two readers, deliberately: the strip and the refresh message must never
 * disagree about what this page depends on. Stock pages are not listed and fall back to prices,
 * which is why the fallback lives here rather than at one call site; health.html declares an
 * empty list on purpose and keeps it (an empty array is not a missing one).
 */
function freshnessKeys(page) {
  return RELEVANT[page] || ['holdingsSynced'];
}

function paintFreshness(health, page) {
  const host = document.getElementById('freshness-inner');
  if (!host) return;

  const nodes = [];

  const marketNode = el('span', {},
    el('span.dot.' + (health.marketOpen ? 'live' : 'closed')),
    health.marketOpen ? 'Market open' : 'Market closed');
  nodes.push(marketNode);

  nodes.push(el('span', {}, el('b', {}, String(health.activeHoldingsCount)), ' holdings'));

  const keys = freshnessKeys(page);
  for (const key of keys) {
    const value = (health.freshness || {})[key];
    const age = daysAgo(value);
    const onDemand = ON_DEMAND.has(key);
    const stale = !onDemand && age !== null && age > STALE_AFTER_DAYS;
    const title = onDemand
      ? (value
        ? `${FRESHNESS_LABELS[key] || key} last updated ${dateTimeIst(value)}. This one runs only `
          + 'when you press the button on the Events page — it is never late.'
        : 'Nothing read yet. This one runs only when you press the button on the Events page.')
      : (value ? `${FRESHNESS_LABELS[key] || key} last updated ${dateTimeIst(value)}` : 'No data recorded yet');
    nodes.push(el('span' + (stale ? '.stale' : ''), {
      title,
    }, `${FRESHNESS_LABELS[key] || key}: ${value ? relative(value) : 'never'}`));
  }

  // The strip is rebuilt wholesale on every health read, so the control has to be re-appended
  // rather than left in the DOM — otherwise the first refresh removes the refresh button.
  if (refreshNode) nodes.push(refreshNode);

  host.replaceChildren(...nodes);
}

// ------------------------------------------------------------------- refresh

/**
 * The refresh control: the button, and the one-line result of the last click beside it.
 */
function buildRefresh(page) {
  if (NO_REFRESH.has(page)) return null;

  const status = el('span.refresh-status', {});
  const btn = el('button.refresh-btn', {
    type: 'button',
    title: 'Reads the app’s stored results again and updates the times shown here. It does '
      + 'not re-run any analysis — the screening, the holdings sync and the rest run on '
      + 'their own schedule, which the Data Health page lists.',
  }, '↻ Refresh');
  btn.addEventListener('click', () => doRefresh(page, btn, status));

  return el('span.refresh-wrap', {}, btn, status);
}

async function doRefresh(page, btn, status) {
  if (refreshing) return;
  refreshing = true;
  btn.disabled = true;
  btn.textContent = '↻ Refreshing…';
  clearTimeout(statusTimer);
  status.replaceChildren();

  // Snapshot before the reload, because the reload is what re-reads health.
  const before = { ...((lastHealth && lastHealth.freshness) || {}) };

  try {
    if (reloadFn) await reloadFn();
    else await loadHealth(page);
    status.replaceChildren(...describeChange(before, page));
  } catch (e) {
    console.error('refresh failed', e);
    // Deliberately not "the app is down": the offline banner owns that claim and knows whether
    // this was a timeout or an unreachable server (B-086). This line only says the click failed.
    status.replaceChildren(el('span.stale', {}, 'Could not refresh just now.'));
  } finally {
    refreshing = false;
    btn.disabled = false;
    btn.textContent = '↻ Refresh';
    statusTimer = setTimeout(() => status.replaceChildren(), STATUS_LINGER_MS);
  }
}

/**
 * What the click actually achieved, in the investor's words.
 *
 * This is the part that keeps the button honest. Everything these screens show is written by a
 * scheduled job, so between two runs a refresh returns byte-identical rows — and saying
 * "updated" then would be a small lie repeated several times a day. So the freshness stamps for
 * the keys THIS page depends on are compared, and the answer is whichever of the two it is.
 */
function describeChange(before, page) {
  const after = (lastHealth && lastHealth.freshness) || {};
  const keys = freshnessKeys(page);

  const moved = keys.filter((k) => (before[k] || null) !== (after[k] || null));
  if (moved.length) {
    const what = moved.map((k) => (FRESHNESS_LABELS[k] || k).toLowerCase());
    const list = what.length === 1 ? what[0] : `${what.slice(0, -1).join(', ')} and ${what[what.length - 1]}`;
    return [el('span.refresh-new', {}, `Updated — new ${list}.`)];
  }

  // Data Health re-runs every check on this click, and declares no freshness keys of its own on
  // purpose (see RELEVANT) — so there is nothing to compare and nothing to claim.
  if (!keys.length) return [el('span.muted', {}, 'Checked again.')];

  return [
    el('span.muted', {}, 'Checked — no new results yet. '),
    el('a', { href: 'health.html' }, 'What runs when'),
  ];
}

/** One health read: refreshes the strip, the shutdown warning and the diff baseline. */
async function loadHealth(page) {
  const { data } = await get('/api/dashboard/health');
  lastHealth = data;
  paintFreshness(data, page);
  maybeWarnShutdown(data);
  return data;
}

/**
 * Warns before the 15:45 shutdown. The app is killed by a Windows scheduled task, so the
 * dashboard vanishes without warning otherwise (SPEC section 27.7).
 */
function maybeWarnShutdown(health) {
  const banner = document.getElementById('banner-shutdown');
  const text = document.getElementById('banner-shutdown-text');
  if (!banner || !text || !health.scheduledShutdownAt) return;

  const now = String(health.serverTimeIst || '').slice(11, 16);
  if (!now) return;

  const mins = (hhmm) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));
  const left = mins(health.scheduledShutdownAt) - mins(now);

  if (left > 0 && left <= 20) {
    text.textContent = `Heads up: the app shuts down at ${health.scheduledShutdownAt} (about ${left} minute${left === 1 ? '' : 's'} away), and the dashboard goes offline with it. Anything you want to read, read now.`;
    banner.classList.add('show');
  } else {
    banner.classList.remove('show');
  }
}

function paintOffline(isOnline, reason) {
  const banner = document.getElementById('banner-offline');
  const text = document.getElementById('banner-offline-text');
  if (!banner || !text) return;

  if (isOnline) {
    banner.classList.remove('show');
    document.querySelectorAll('.stale-content').forEach((n) => n.classList.remove('stale-content'));
    return;
  }

  // B-086. A request that timed out and a server that is not there are different facts,
  // and only one of them is fixed by start-app.bat. Sending the investor to restart an
  // app that is already running wastes their time and teaches them to distrust the
  // banner - the same reasoning api.js already applies to a 4xx/5xx.
  const message = reason === 'timeout'
    ? 'The app is running but did not answer in time, so this page is showing your last '
      + 'saved view. That usually means it is still warming up — give it a moment, '
      + 'then try again.'
    : 'The app is not running right now, so this page is showing your last saved view. '
      + 'It runs 9:00 AM to 3:45 PM on weekdays — to see live data, run start-app.bat.';

  text.replaceChildren(
    document.createTextNode(message),
    el('button', { onclick: () => window.location.reload() }, 'Try again'));
  banner.classList.add('show');
  document.querySelectorAll('main .section').forEach((n) => n.classList.add('stale-content'));
}

/**
 * Installs the chrome (once) and loads /api/dashboard/health.
 *
 * Idempotent by design: a page hands its whole entry function to {@link registerRefresh}, so
 * this runs again on every refresh. Prepending a second header each time is how that would go
 * wrong, so the DOM half happens only when the chrome is not already there.
 *
 * @returns {Promise<object|null>} the health payload, or null when unreachable
 */
export async function initChrome() {
  const page = currentPage();

  if (!document.querySelector('header.topbar')) {
    refreshNode = buildRefresh(page);
    document.body.prepend(...buildChrome(page));
    onConnectionChange(paintOffline);
  }

  try {
    return await loadHealth(page);
  } catch (e) {
    const host = document.getElementById('freshness-inner');
    if (host) {
      // The control is re-appended here too: being unable to reach the app is the moment the
      // investor most wants to try again, so removing the button would be exactly backwards.
      host.replaceChildren(
        el('span', {}, el('span.dot.down'), 'App not running — showing saved data where available'),
        ...(refreshNode ? [refreshNode] : []));
    }
    paintOffline(false);
    return null;
  }
}
