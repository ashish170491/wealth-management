/**
 * IPOs - the pipeline of new issues and what became of the recent ones (SPEC 45).
 *
 * Two questions, kept apart on purpose:
 *
 *  1. Before it lists: what does the STRUCTURE of the offer say? The app has no accounts for a
 *     company that has not listed yet, so it cannot run a single quality check on it. It reads
 *     the two things the exchange feed does carry - how much of the money goes into the business
 *     versus to sellers, and who filled the book once bidding closed - and then hands the reader
 *     a short list of what to read in the prospectus themselves. It never says "apply".
 *
 *  2. After it lists: where in the post-IPO cycle is it, and is it a business worth owning? The
 *     lock-in calendar names the dates the sellers arrive; the six-month rule (SPEC 30.4) is why
 *     nothing younger is judged; the quality columns come from the first filings as a listed
 *     company, fetched on demand and stored so this page stays DB-only on load.
 *
 * Null discipline (Gotcha 21): an unparsed issue-size sentence, an unpublished subscription and a
 * listing with no broker price history all render as "not measured", never as 0 or as a verdict.
 */

import { get, post, OfflineError } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import { chipFilters } from './filters.js';
import { analyseButton } from './ipo-analyse.js';
import {
  inr, inrExact, num, pct, displaySymbol, stockHref, missing, shortDate, dateTimeIst,
} from './format.js';
import {
  el, section, kpi, card, empty, skeleton, mount, badge, table, alert, withCount, unmeasured,
} from './ui.js';
import { loadWatchedSet, watchButton } from './watch-button.js';

const view = document.getElementById('view');

/** Symbols already on the watchlist - filled at boot so every "+ Watch" button knows its state. */
let watched = new Set();

// ------------------------------------------------------------------ small cells

const STRUCTURE = {
  FAVOURABLE: { type: 'success', label: 'Structure favourable' },
  MIXED: { type: 'warning', label: 'Mixed' },
  UNFAVOURABLE: { type: 'danger', label: 'Unfavourable' },
};

function structureCell(r) {
  const s = r.structure || {};
  if (!s.verdict || s.verdict === 'NOT_MEASURED') {
    return unmeasured((s.reasons && s.reasons[0]) || 'Nothing about this issue could be judged yet');
  }
  const meta = STRUCTURE[s.verdict] || { type: 'info', label: s.verdict };
  const wrap = el('div', {}, badge(s.verdict, meta));
  for (const reason of s.reasons || []) {
    wrap.append(el('div.faint', { style: 'font-size:11.5px;margin-top:3px;max-width:340px' }, reason));
  }
  return wrap;
}

function structureRank(r) {
  const v = r.structure && r.structure.verdict;
  return { FAVOURABLE: 3, MIXED: 2, UNFAVOURABLE: 1 }[v] ?? null;
}

/** "62% to the company" with the rupee split underneath; unmeasured when the sentence did not parse. */
function moneyCell(r) {
  if (missing(r.freshSharePct)) {
    return unmeasured('NSE’s description of the issue could not be read into a fresh-versus-sale split');
  }
  const f = r.freshSharePct;
  const cls = f >= 50 ? 'pos' : f < 25 ? 'neg' : '';
  return el('div', {},
    el('div', { class: cls, style: 'font-weight:600' }, `${Math.round(f)}% to the company`),
    el('div.faint', { style: 'font-size:11.5px' },
      `fresh ${inr(r.freshIssueCr * 1e7)} · sellers ${inr(r.offerForSaleCr * 1e7)}`));
}

function bandCell(r) {
  if (missing(r.priceBandHigh)) return unmeasured('Price band not published yet');
  const low = missing(r.priceBandLow) ? r.priceBandHigh : r.priceBandLow;
  const text = low === r.priceBandHigh ? inrExact(r.priceBandHigh) : `${inrExact(low)}–${inrExact(r.priceBandHigh)}`;
  return el('div', {}, el('div', {}, text),
    r.faceValue ? el('div.faint', { style: 'font-size:11.5px' }, `face value ${inrExact(r.faceValue)}`) : null);
}

function lotCell(r) {
  const s = r.sizing || {};
  if (missing(s.lotCostRs)) return unmeasured(s.note || 'Lot size or band not published yet');
  return el('div', {},
    el('div', { style: 'font-weight:600' }, inr(s.lotCostRs)),
    el('div.faint', { style: 'font-size:11.5px' }, `${s.lotSize} shares · retail max ${s.retailMaxLots} lot${s.retailMaxLots === 1 ? '' : 's'}`));
}

function subscriptionCell(r) {
  if (missing(r.qibTimes) && missing(r.niiTimes) && missing(r.retailTimes)) {
    return unmeasured('No subscription figures published yet');
  }
  const x = (v) => (missing(v) ? '—' : `${Number(v).toFixed(2)}×`);
  const line = (label, v) => el('div', { style: 'display:flex;gap:8px;font-size:12.5px' },
    el('span.muted', { style: 'min-width:82px' }, label), el('span.num', { style: 'font-weight:600' }, x(v)));
  const wrap = el('div', {},
    line('Institutions', r.qibTimes),
    line('Big investors', r.niiTimes),
    line('Retail', r.retailTimes));
  if (!missing(r.employeeTimes)) wrap.append(line('Employees', r.employeeTimes));
  if (!missing(r.shareholderTimes)) wrap.append(line('Shareholders', r.shareholderTimes));
  wrap.append(el('div.faint', { style: 'font-size:11px;margin-top:3px' },
    r.subscriptionFinal
      ? `final · read ${dateTimeIst(r.subscriptionAsOf)}`
      : `mid-issue snapshot, ${dateTimeIst(r.subscriptionAsOf)} — institutions bid on the last afternoon`));
  return wrap;
}

function quotaCell(r) {
  const parts = [];
  if (r.employeeQuota) {
    parts.push(badge('EMPLOYEE', { type: 'info', label: missing(r.employeeDiscountRs) ? 'Employee quota' : `Employee quota, ${inrExact(r.employeeDiscountRs)} off` }));
  }
  if (r.shareholderQuota) {
    parts.push(badge('SHAREHOLDER', { type: 'success', label: 'Shareholder quota' }));
  }
  if (!parts.length) return el('span.faint', {}, 'none');
  return el('div', { style: 'display:flex;flex-direction:column;gap:4px' }, ...parts);
}

function linksCell(r) {
  const links = [];
  if (r.rhpUrl) links.push(el('a', { href: r.rhpUrl, target: '_blank', rel: 'noopener' }, 'Prospectus'));
  if (r.ratiosUrl) links.push(el('a', { href: r.ratiosUrl, target: '_blank', rel: 'noopener' }, 'Basis of price'));
  if (r.anchorUrl) links.push(el('a', { href: r.anchorUrl, target: '_blank', rel: 'noopener' }, 'Anchor book'));
  if (!links.length) return el('span.faint', {}, '—');
  return el('div', { style: 'display:flex;flex-direction:column;gap:3px;font-size:12.5px' }, ...links);
}

function whenCell(r) {
  if (r.status === 'OPEN') {
    return el('div', {}, el('div', { style: 'font-weight:600' }, `Day ${r.dayOfIssue} of ${r.issueDays}`),
      el('div.faint', { style: 'font-size:11.5px' }, `closes ${shortDate(r.issueEndDate)}`));
  }
  if (r.status === 'FORTHCOMING') {
    return el('div', {}, el('div', { style: 'font-weight:600' }, `Opens ${shortDate(r.issueStartDate)}`),
      el('div.faint', { style: 'font-size:11.5px' }, `in ${r.daysToOpen} day${r.daysToOpen === 1 ? '' : 's'} · closes ${shortDate(r.issueEndDate)}`));
  }
  return el('div', {}, el('div', { style: 'font-weight:600' }, `Closed ${shortDate(r.issueEndDate)}`),
    el('div.faint', { style: 'font-size:11.5px' }, missing(r.issuePrice) ? 'awaiting listing' : `priced at ${inrExact(r.issuePrice)}`));
}

function companyCell(r) {
  return el('div', {},
    el('div', { style: 'font-weight:600' }, displaySymbol(r.symbol)),
    el('div.faint', { style: 'font-size:12px;max-width:220px' }, r.companyName || '—'));
}

// ------------------------------------------------------------------ pipeline

function pipelineCols({ withSubscription, withOdds }) {
  const cols = [
    { key: 'symbol', label: 'Issue', render: companyCell },
    { key: 'issueEndDate', label: 'When', render: whenCell, value: (r) => r.issueEndDate },
    { key: 'priceBandHigh', label: 'Price band', align: 'r', render: bandCell },
    { key: 'lotCostRs', label: 'One lot costs', align: 'r', value: (r) => (r.sizing || {}).lotCostRs, render: lotCell },
    { key: 'issueSizeCr', label: 'Issue size', align: 'r', render: (r) => (missing(r.issueSizeCr) ? unmeasured('Issue size not parsed') : inr(r.issueSizeCr * 1e7)) },
    { key: 'freshSharePct', label: 'Where the money goes', render: moneyCell },
    { key: 'structure', label: 'What the structure says', value: structureRank, render: structureCell },
  ];
  if (withSubscription) {
    cols.push({ key: 'qibTimes', label: 'Times subscribed', render: subscriptionCell });
  }
  if (withOdds) {
    cols.push({
      key: 'retailOddsOneIn',
      label: 'Retail odds',
      align: 'r',
      render: (r) => (missing(r.retailOddsOneIn)
        ? unmeasured('Final retail figure not published yet')
        : el('span', { title: 'When the retail book is oversubscribed every applicant has an equal chance of one lot' },
          r.retailOddsOneIn <= 1 ? 'everyone allotted' : `about 1 in ${num(r.retailOddsOneIn)}`)),
    });
  }
  cols.push(
    { key: 'employeeQuota', label: 'Quotas', sortable: false, render: quotaCell },
    { key: 'rhpUrl', label: 'Read', sortable: false, render: linksCell },
  );
  return cols;
}

function pipelineSection(p) {
  const explain = 'Every mainboard issue that is open, about to open, or closed and waiting to list. '
    + 'The app has no accounts for a company that has not listed yet, so it cannot judge the '
    + 'business — what it reads is the structure of the offer: how much of the money goes '
    + 'into the company rather than to sellers, and who filled the book. That is a reason to '
    + 'read further or to stop, not a reason to apply.';

  if (!p) {
    return section('New issues', explain, empty('Not available', 'The IPO pipeline could not be loaded.'));
  }

  const open = p.open || [];
  const forthcoming = p.forthcoming || [];
  const closed = p.closedAwaitingListing || [];

  const kpis = el('div.grid.kpis', {},
    kpi({ label: 'Open for bidding', value: num(open.length), sub: 'mainboard only', tone: open.length ? 'positive' : 'neutral' }),
    kpi({ label: 'Opening soon', value: num(forthcoming.length), sub: 'announced by NSE', tone: 'neutral' }),
    kpi({ label: 'Closed, awaiting listing', value: num(closed.length), sub: 'final book readable', tone: 'neutral' }),
    kpi({ label: 'Feed last read', value: p.capturedAt ? dateTimeIst(p.capturedAt) : 'never', sub: 'refreshed each weekday at 12:15', tone: p.capturedAt ? 'neutral' : 'unmeasured' }));

  const blocks = [];
  if (open.length) {
    blocks.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'Open now'),
      table(pipelineCols({ withSubscription: true, withOdds: false }), open, { sortKey: 'issueEndDate', sortDir: 'asc' }));
  }
  if (forthcoming.length) {
    blocks.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'Opening soon'),
      table(pipelineCols({ withSubscription: false, withOdds: false }), forthcoming, { sortKey: 'issueEndDate', sortDir: 'asc' }));
  }
  if (closed.length) {
    blocks.push(el('div.muted', { style: 'font-weight:600;margin:14px 0 6px' }, 'Closed, waiting to list'),
      table(pipelineCols({ withSubscription: true, withOdds: true }), closed, { sortKey: 'issueEndDate' }));
  }
  if (!blocks.length) {
    blocks.push(empty('No mainboard issues in the pipeline',
      p.capturedAt ? 'NSE lists nothing open or forthcoming right now. SME issues are excluded by design.'
        : 'The feed has not been read yet. Use the button above, or wait for the 12:15 capture.'));
  }

  const structured = [...open, ...forthcoming, ...closed].filter((r) => r.structure && r.structure.structureMeasured).length;
  const total = open.length + forthcoming.length + closed.length;
  const coverage = total ? el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `Fresh-versus-sale split read for ${structured} of ${total} issues. Where NSE’s wording could not be `
    + 'parsed the structure is shown as not measured rather than guessed.') : null;

  return withCount(section('New issues', explain, card(kpis, captureButton(), ...blocks, coverage)), total);
}

/**
 * "Capture from NSE now" — the one control on this page that leaves the building.
 *
 * Two things about it are deliberate.
 *
 * It is NOT called "refresh". The chrome carries a Refresh control on every screen and that one
 * only re-reads the database (SPEC 27.12); this one spends a few dozen NSE calls and a paced
 * Kite quote per recent listing. Two controls on one screen sharing a word while meaning
 * different things is the failure Gotcha 85 is about, and it happened here: this button used to
 * read "Refresh from NSE now", sitting a few centimetres under a button reading "Refresh".
 *
 * And it asks first. Everything else the dashboard does on a click is a database read; this is
 * the one that costs minutes of somebody else's rate limit, so the reader is told what it will
 * do and what it will NOT do before it happens, rather than after (SPEC 27.14).
 */
const CAPTURE_TIMEOUT_MS = 420000;

function captureButton() {
  const host = el('div', { style: 'margin:4px 0 8px' });
  const status = el('div.muted', { style: 'font-size:12.5px;margin-top:7px' });

  const button = el('button.action.secondary.compact', {
    type: 'button',
    title: 'Fetches NSE’s IPO feeds live. Asks for confirmation first, and tells you what it costs.',
  }, 'Capture from NSE now');

  const idle = () => {
    button.disabled = false;
    button.textContent = 'Capture from NSE now';
    host.replaceChildren(button, status);
  };

  async function fire() {
    button.disabled = true;
    button.textContent = 'Reading NSE…';
    host.replaceChildren(button, status);
    status.textContent = 'This usually takes about five minutes. Leave the page open — closing it '
      + 'does not stop the capture, but you will not see the result.';
    try {
      const r = await post('/api/ipo/capture', undefined, CAPTURE_TIMEOUT_MS);
      status.textContent = (r.note || 'Done.') + ' Reloading…';
      setTimeout(() => window.location.reload(), 1400);
    } catch (err) {
      // A 409 arrives with the guard's own sentence in it (Gotcha 60) — show it verbatim, since
      // explaining WHY it was refused is the entire reason that guard carries a reason.
      status.textContent = err instanceof OfflineError
        ? 'The app is not reachable right now.'
        : String(err.message || err);
      idle();
    }
  }

  function confirmPanel() {
    const yes = el('button.action.compact', { type: 'button' }, 'Yes, capture now');
    const no = el('button.action.secondary.compact', { type: 'button' }, 'Cancel');
    yes.addEventListener('click', fire);
    no.addEventListener('click', () => { status.textContent = ''; idle(); });

    return el('div.alert.warning', {},
      el('div.alert-title', {}, 'This one goes out to NSE — shall I?'),
      el('div', {}, 'It reads three NSE list pages, then one detail page per issue in the pipeline, '
        + 'and refreshes a price for each recent listing through your broker. Today’s scheduled '
        + 'run took just over five minutes. It writes what it finds to the database.'),
      el('div', { style: 'margin-top:7px' },
        el('b', {}, 'What it will not do: '),
        'it will not make the structure read decide anything sooner. That deliberately waits until '
        + 'bidding has closed, because institutions bid on the last afternoon — so a subscription '
        + 'figure taken mid-issue is not evidence yet. You will see the book filling and fresher '
        + 'prices, not an earlier verdict.'),
      el('div.muted', { style: 'margin-top:7px' },
        'It is refused between 09:40–10:15 and from 14:00 to the close on a trading day, because '
        + 'the FII/DII fetch, the 14:00 screening and the afternoon report jobs share this NSE '
        + 'session and the broker rate limit. If it is refused you will be told why.'),
      el('div', { style: 'margin-top:11px;display:flex;gap:9px;flex-wrap:wrap' }, yes, no));
  }

  button.addEventListener('click', () => {
    status.textContent = '';
    host.replaceChildren(confirmPanel(), status);
  });

  host.append(button, status);
  return host;
}

// ------------------------------------------------------------------ before you apply

function categoriesSection(p) {
  const retailCap = (p && p.retailCapRs) || 200000;
  const sniiCap = (p && p.smallNiiCapRs) || 1000000;
  const empCap = (p && p.employeeCapRs) || 500000;
  const upiCap = (p && p.upiCapRs) || 500000;

  const row = (title, who, how, odds) => el('div', { style: 'display:grid;grid-template-columns:150px 1fr 1fr 1fr;gap:12px;padding:9px 0;border-bottom:1px solid var(--rule);font-size:13px' },
    el('div', { style: 'font-weight:600' }, title),
    el('div', {}, who), el('div', {}, how), el('div.muted', {}, odds));

  const head = el('div', { style: 'display:grid;grid-template-columns:150px 1fr 1fr 1fr;gap:12px;padding:0 0 6px;font-size:12px;font-weight:600;color:var(--ink-muted);border-bottom:2px solid var(--navy-tint)' },
    el('div', {}, 'Category'), el('div', {}, 'Who it is for'), el('div', {}, 'How to apply'), el('div', {}, 'How allotment works'));

  const grid = el('div', {},
    head,
    row('Retail', `Anyone applying up to ${inr(retailCap)} in one issue. This is you.`,
      'Bid at "cut-off" (you accept whatever price is discovered). One application per PAN; each family member with their own demat and bank account can apply separately.',
      'When oversubscribed, every applicant has an equal chance of one lot by lottery, however many lots they asked for. So apply for one lot per person, not ten in one account.'),
    row('Small non-institutional', `Applications above ${inr(retailCap)} up to ${inr(sniiCap)}.`,
      `You must bid at a price, not at cut-off. Above ${inr(upiCap)} the payment cannot go through UPI — it goes through your bank’s ASBA form.`,
      'Also a lottery for the minimum lot count when oversubscribed, and this pool is usually the most crowded of all. Committing several lakh for a lottery ticket is not a plan.'),
    row('Big non-institutional', `Applications above ${inr(sniiCap)}.`,
      'Bank ASBA only.',
      'Irrelevant to a retail-sized portfolio, and the money is blocked for a week either way.'),
    row('Employee quota', 'Permanent employees of the company on the date named in the prospectus.',
      `Apply in the employee category, up to ${inr(empCap)} in total. Often carries a discount to the issue price.`,
      'Much less crowded than retail, so allotment is far more likely. Only worth it if you would hold the company you work for — that already concentrates your income and your savings in one place.'),
    row('Shareholder quota', 'People who already hold shares of the PARENT company — used when a listed company floats a subsidiary.',
      `Hold at least one share of the parent in your demat on the record date, which is the day the prospectus is filed with the Registrar of Companies (about a week before the issue opens). Apply in the shareholder category up to ${inr(retailCap)} — and you may ALSO apply in retail with the same PAN; the rules treat the two as separate.`,
      'The one real edge a small investor has: the reserved portion is usually far less oversubscribed than retail, so the odds of one lot are several times better. The app flags the quota on each issue; the prospectus names the parent.'));

  const stance = el('div', {
    style: 'padding:11px 13px;background:var(--warn-bg);border-left:3px solid var(--warn);border-radius:var(--radius);font-size:13px;margin-top:14px',
  },
  el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'The only honest reason to apply'),
  el('div', {},
    'Apply only if you would be happy to buy the company at the top of the band on listing day and hold it '
    + 'for five years. That is the whole test. Applying for a listing-day pop is a lottery where the odds of '
    + 'getting a lot are one in thirty in the popular issues, the reward is a few thousand rupees, and the '
    + 'issues where allotment is easy are exactly the ones nobody wanted. Grey-market premiums are not shown '
    + 'here, deliberately: they are the price of the lottery ticket, not information about the business.'));

  const checklist = el('div', { style: 'margin-top:14px;font-size:13px' },
    el('div', { style: 'font-weight:600;margin-bottom:6px' }, 'What to read in the prospectus before you decide (30 minutes)'),
    el('ol', { style: 'margin:0 0 0 20px' },
      el('li', { style: 'margin-bottom:5px' }, el('b', {}, 'Objects of the issue. '), 'If most of the money repays debt or pays out existing holders, the business is not being funded — the sellers are being paid. The "where the money goes" column above is this in one number.'),
      el('li', { style: 'margin-bottom:5px' }, el('b', {}, 'Who is selling. '), 'A founder selling a slice to meet the public-float rule is normal. A private-equity fund selling most of its stake at the top of a hot market is telling you something.'),
      el('li', { style: 'margin-bottom:5px' }, el('b', {}, 'Three years of profit and cash flow, side by side. '), 'Profit that jumped only in the year before the IPO, or profit that never turned into cash, is the pattern that unwinds after listing.'),
      el('li', { style: 'margin-bottom:5px' }, el('b', {}, 'The price compared with listed peers. '), 'The "basis of issue price" document lists them. An issue priced above every listed competitor needs a reason you can say out loud.'),
      el('li', { style: 'margin-bottom:5px' }, el('b', {}, 'Promoter holding after the issue, and any pledges. '), 'Below 50% with pledged shares is the combination the app treats as a red flag on listed stocks too.'),
      el('li', {}, el('b', {}, 'Litigation and related-party dealings. '), 'Skim the risk factors for anything involving the promoters personally.')));

  return section('Before you apply',
    'The categories you can apply under, how allotment actually works, and the one question that decides it. '
    + 'The figures are SEBI’s rules, not the app’s opinion.',
    card(grid, stance, checklist));
}

// ------------------------------------------------------------------ recent listings

const STAGE = {
  HYPE_WINDOW: { type: 'neutral', label: 'Too early — lock-in ahead' },
  WASHOUT: { type: 'warning', label: 'Below listing high' },
  RECOVERING: { type: 'info', label: 'Recovering' },
  BASE_FORMING: { type: 'success', label: 'Base forming' },
};

function stageCell(r) {
  if (!r.stage || r.stage === 'NOT_MEASURED') return unmeasured(r.stageReason || 'No price history from the broker');
  const meta = STAGE[r.stage] || { type: 'info', label: r.stage };
  return el('div', {}, badge(r.stage, meta),
    el('div.faint', { style: 'font-size:11.5px;margin-top:3px;max-width:300px' }, r.stageReason || ''));
}

function stageRank(r) {
  return { BASE_FORMING: 4, RECOVERING: 3, WASHOUT: 2, HYPE_WINDOW: 1 }[r.stage] ?? null;
}

function priceNowCell(r) {
  if (missing(r.latestPrice)) return unmeasured('No price on record yet — use Analyse, or wait for the daily capture');
  const v = r.vsIssuePricePct;
  return el('div', {},
    el('div', { style: 'font-weight:600' }, inrExact(r.latestPrice, true)),
    missing(v) ? el('div.faint', { style: 'font-size:11.5px' }, 'issue price unknown')
      : el('div', { class: v > 0 ? 'pos' : v < 0 ? 'neg' : '', style: 'font-size:11.5px' }, `${pct(v)} vs issue price`),
    r.latestPriceDate ? el('div.faint', { style: 'font-size:11px' }, `as of ${shortDate(r.latestPriceDate)}`) : null);
}

function listingDayCell(r) {
  if (missing(r.listingDayClose)) return unmeasured('Listing-day candle not fetched yet');
  const v = r.vsListingCloseFromIssuePct;
  return el('div', {},
    el('div', {}, `closed ${inrExact(r.listingDayClose, true)}`),
    missing(v) ? null : el('div', { class: v > 0 ? 'pos' : v < 0 ? 'neg' : '', style: 'font-size:11.5px' }, `${pct(v)} on day one`),
    missing(r.listingDayHigh) ? null : el('div.faint', { style: 'font-size:11px' }, `day high ${inrExact(r.listingDayHigh, true)}`));
}

function nextLockInCell(r) {
  const cal = r.lockIn || [];
  const next = cal.find((m) => !m.passed);
  if (!next) return el('span.faint', {}, 'all lock-ins cleared');
  const days = next.daysAway;
  return el('div', {}, el('div', { style: 'font-weight:600;font-size:12.5px' }, next.label),
    el('div.faint', { style: 'font-size:11.5px' }, `${shortDate(next.approxDate)} · in ${days} day${days === 1 ? '' : 's'}`));
}

function qualityCell(r) {
  if (!r.lastAnalysedAt) return unmeasured('Not analysed yet — use the Analyse button');
  const parts = [];
  parts.push(el('div', { style: 'font-size:12.5px' }, el('span.muted', {}, 'Earnings: '),
    r.earningsVerdict ? badge(r.earningsVerdict) : unmeasured('No quarterly results filed as a listed company yet')));
  parts.push(el('div', { style: 'font-size:12.5px;margin-top:2px' }, el('span.muted', {}, 'Accounts: '),
    r.financialQualityVerdict ? badge(r.financialQualityVerdict) : unmeasured('No filings to judge the balance sheet on yet')));
  const prom = missing(r.promoterHoldingPct) ? 'not measured' : `${Number(r.promoterHoldingPct).toFixed(1)}%`;
  const pledge = missing(r.promoterPledgePct) ? '' : `, ${Number(r.promoterPledgePct).toFixed(1)}% pledged`;
  parts.push(el('div.faint', { style: 'font-size:11.5px;margin-top:2px' }, `promoters hold ${prom}${pledge}`));
  if (!missing(r.compositeScore)) {
    parts.push(el('div.faint', { style: 'font-size:11.5px' }, `screening composite ${r.compositeScore} (not published to the screener)`));
  }
  return el('div', {}, ...parts);
}

// analyseButton now lives in ipo-analyse.js — Discovery's Recent Listings table needs the
// same control, and two copies of a live-fetch button is two things to keep in step.

/**
 * Recent-listing chips (SPEC 27.13). The stage group is the one that matters: it is how the
 * reader gets to "base forming" — the only bucket this page suggests researching — without
 * scrolling three years of listings. NOT_MEASURED keeps its own chip and is never folded into
 * WASHOUT, which is the distinction SPEC 45.4 exists to protect.
 */
const RECENT_FILTERS = chipFilters([
  {
    label: 'Where in the cycle:',
    key: 'stage',
    options: [
      { value: 'ALL', text: 'All' },
      {
        value: 'BASE_FORMING', text: 'Base forming', test: (r) => r.stage === 'BASE_FORMING',
        title: 'Past the lock-in cliff and no longer falling. The bucket worth reading a prospectus for.',
      },
      { value: 'RECOVERING', text: 'Recovering', test: (r) => r.stage === 'RECOVERING' },
      { value: 'WASHOUT', text: 'Washed out', test: (r) => r.stage === 'WASHOUT' },
      {
        value: 'HYPE_WINDOW', text: 'Still in the hype window', test: (r) => r.stage === 'HYPE_WINDOW',
        title: 'Listed under six months ago. Shown, never judged — the sellers have not arrived yet.',
      },
      {
        value: 'NOT_MEASURED', text: 'Not measured',
        test: (r) => !r.stage || r.stage === 'NOT_MEASURED',
        title: 'No usable price history. A gap in the app, not a verdict on the company.',
      },
    ],
  },
  {
    label: 'Offer was:',
    key: 'structure',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'FAVOURABLE', text: 'Favourable', test: (r) => r.structure && r.structure.verdict === 'FAVOURABLE' },
      { value: 'MIXED', text: 'Mixed', test: (r) => r.structure && r.structure.verdict === 'MIXED' },
      { value: 'UNFAVOURABLE', text: 'Unfavourable', test: (r) => r.structure && r.structure.verdict === 'UNFAVOURABLE' },
    ],
  },
  {
    label: 'Against issue price:',
    key: 'vsIssue',
    options: [
      { value: 'ALL', text: 'All' },
      { value: 'UP', text: 'Above it', test: (r) => !missing(r.vsIssuePricePct) && r.vsIssuePricePct > 0 },
      { value: 'DOWN', text: 'Below it', test: (r) => !missing(r.vsIssuePricePct) && r.vsIssuePricePct < 0 },
    ],
  },
  {
    key: 'analysed',
    toggle: true,
    text: 'Analysed only',
    title: 'Listings whose first filings as a listed company have been fetched, so the business has a quality read.',
    test: (r) => !!r.lastAnalysedAt,
  },
  { search: true, placeholder: 'Search a listing…' },
], () => render());

function recentSection(data, months, onMonths) {
  const explain = 'Every mainboard listing of the last three years and where it is in its cycle. Newly listed '
    + 'companies produce a lot of multibaggers, but almost never during the excitement around the listing: '
    + 'the sellers arrive on a timetable — anchor investors after 30 and 90 days, early investors after '
    + 'six months — and the app deliberately judges nothing younger than that. The stage column says '
    + 'which question to ask; the quality column, once analysed, answers whether the business is worth it.';

  const picker = el('div', { style: 'display:flex;gap:6px;align-items:center;margin-bottom:8px;font-size:12.5px' },
    el('span.muted', {}, 'Show listings from the last'),
    ...[6, 12, 36].map((m) => el('button.action.secondary.compact', {
      onclick: () => onMonths(m),
      style: m === months ? 'font-weight:700;border-color:var(--navy)' : '',
    }, `${m} months`)));

  if (!data) {
    return section('Recently listed', explain, card(picker, empty('Not available', 'Recent listings could not be loaded.')));
  }

  const rows = data.listings || [];
  const cols = [
    { key: 'symbol', label: 'Stock', render: (r) => el('div', {}, el('a', { href: stockHref(r.symbol), title: r.symbol }, displaySymbol(r.symbol)), el('div.faint', { style: 'font-size:11.5px;max-width:200px' }, r.companyName || '')) },
    { key: 'watch', label: '', sortable: false, render: (r) => watchButton(r.symbol, watched, { note: 'From the IPO page: recent listing' }) },
    { key: 'listingDate', label: 'Listed', render: (r) => el('div', {}, el('div', {}, shortDate(r.listingDate)), el('div.faint', { style: 'font-size:11.5px' }, `${r.monthsSinceListing} mo ago`)), value: (r) => r.listingDate },
    { key: 'issuePrice', label: 'Issue price', align: 'r', render: (r) => (missing(r.issuePrice) ? unmeasured('Final price not published') : inrExact(r.issuePrice, true)) },
    { key: 'listingDayClose', label: 'Listing day', render: listingDayCell },
    { key: 'vsIssuePricePct', label: 'Now', align: 'r', render: priceNowCell },
    { key: 'stage', label: 'Where in the cycle', value: stageRank, render: stageCell },
    { key: 'lockIn', label: 'Next lock-in expiry', sortable: false, render: nextLockInCell },
    { key: 'freshSharePct', label: 'Money went to', render: moneyCell },
    { key: 'structure', label: 'Structure was', value: structureRank, render: structureCell },
    { key: 'quality', label: 'Business quality', sortable: false, render: qualityCell },
    { key: 'analyse', label: '', sortable: false, render: analyseButton },
  ];

  const bases = rows.filter((r) => r.stage === 'BASE_FORMING').length;
  const early = rows.filter((r) => r.stage === 'HYPE_WINDOW').length;
  const kpis = el('div.grid.kpis', {},
    kpi({ label: 'Listings in window', value: num(rows.length), sub: `${months} months`, tone: 'neutral' }),
    kpi({ label: 'Still inside the hype window', value: num(early), sub: 'not judged, on purpose', tone: 'neutral' }),
    kpi({ label: 'Base forming', value: num(bases), sub: 'research these first', tone: bases ? 'positive' : 'neutral' }),
    kpi({ label: 'Analysed', value: `${num(data.analysedCount)} of ${num(rows.length)}`, sub: 'quality fetched on demand', tone: 'neutral' }));

  const vocabLink = el('div', { style: 'margin:2px 0 10px;font-size:12.5px' }, el('a', { href: 'guide.html#ipo-stages' }, 'What do “base forming”, “recovering” and “washed out” mean? →'));

  const shown = RECENT_FILTERS.apply(rows);
  const body = rows.length
    // filter:false — the search for this table lives in the chip bar with the chips it works
    // alongside, rather than as a second box below them (Gotcha 85).
    ? el('div', {}, vocabLink, RECENT_FILTERS.bar(rows, shown.length, 'listings'),
      table(cols, shown, { sortKey: 'listingDate', filter: false }))
    : empty('No listings on record for this window', 'Use "Capture from NSE now" above, or wait for the 12:15 capture.');

  const coverage = rows.length ? el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    `Stage judged for ${rows.filter((r) => r.stage && r.stage !== 'NOT_MEASURED').length} of ${rows.length}; `
    + `quality analysed for ${data.analysedCount} of ${rows.length}. A listing without price history or `
    + 'without its first filings shows not measured — that is the app’s gap, not the company’s verdict. '
    + 'Prices for listings older than a year are refreshed only when you analyse them.') : null;

  return withCount(section('Recently listed', explain, card(picker, kpis, body, coverage)), rows.length);
}

// ------------------------------------------------------------------ boot

let monthsShown = 12;

async function render() {
  view.replaceChildren(el('section.section', {}, el('h2.section-title', {}, 'Loading IPOs…'), skeleton(4)));
  await initChrome();

  let pipeline = null;
  let recent = null;
  try {
    [pipeline, recent, watched] = await Promise.all([
      // Both DB-only (SPEC 45.7), verified by hand - a page-load get() is ungated (Gotcha 39).
      get('/api/ipo/pipeline', { fallback: null }).then((r) => r.data).catch(() => null),
      get(`/api/ipo/recent?months=${monthsShown}`, { fallback: null }).then((r) => r.data).catch(() => null),
      loadWatchedSet(),
    ]);
  } catch (err) {
    mount(view, empty('Could not load IPO data', String(err.message || err)));
    return;
  }

  const intro = el('div', { style: 'margin-bottom:6px' },
    el('h1', { style: 'font-size:23px;color:var(--navy)' }, 'IPOs'),
    el('div.muted', { style: 'font-size:13.5px' },
      'New issues, the categories you can apply under, and what became of the recent listings.'));

  const banner = alert({
    severity: 'INFO',
    title: 'What this page will not do',
    message: 'It never says "apply". Before a company lists there are no accounts for the app to read, so '
      + 'every one of its quality checks is silent. What it can read is the structure of the offer and, after '
      + 'listing, the calendar of when the sellers arrive. Treat a favourable structure as permission to spend '
      + 'thirty minutes with the prospectus, not as a verdict.',
  });

  mount(view,
    intro,
    banner,
    pipelineSection(pipeline),
    categoriesSection(pipeline),
    recentSection(recent, monthsShown, (m) => { monthsShown = m; render(); }));
}

// Hand the shared refresh control this page's own reload (SPEC 27.12). Registered AFTER the
// first load, not before it: a click landing while the initial load is still in flight would
// otherwise start a second one on top of it.
render().then(() => registerRefresh(render)).catch((err) => {
  console.error(err);
  mount(view, section('Something went wrong', '',
    empty('Could not load IPOs', String(err && err.message ? err.message : err))));
});
