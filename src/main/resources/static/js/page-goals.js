/**
 * Goals - what you are saving for, and what the dates imply (SPEC 53).
 *
 * Why this screen exists, and why it is the only screen in the app that makes a recommendation
 * of any kind.
 *
 * Everything else here refuses to tell the investor what to do, and for a good reason: those
 * questions - which fund, which stock, is now a good time - all need a forecast, and this app
 * measured on its own data that the forecasts on offer do not work (SPEC 52.13, 39.5, 25). The
 * split between equity and debt for money with a DATE on it is different in kind. It needs no
 * view on the market at all. It follows from one fact the investor supplies, which is when they
 * need the money, and one fact the app can measure, which is what the worst stretch of that
 * length actually did.
 *
 * So this page will say "this money should not be in shares" and mean it. What it will still not
 * do is name a fund, name a stock, or tell anyone to place a trade.
 *
 * Four things on this page are load-bearing and easy to get wrong later:
 *
 *   1. THE PERCENTAGES ARE A STATED RULE, NOT A MEASUREMENT, and the page says so in the same
 *      breath as it shows them. What IS measured sits beside them: the worst window of exactly
 *      that length in this app's fund archive, and what it would have done to the split proposed.
 *      A reader who thinks the rule is too cautious can see the number it is protecting against.
 *   2. AN UNSTATED EARMARK IS BLANK, NEVER ZERO. This app cannot tell which of your units belong
 *      to which goal and does not try. A goal with no earmark shows no shortfall and no monthly
 *      figure, rather than a shortfall equal to the whole goal (Gotcha 21 with a rupee sign on it).
 *   3. THE MONTHLY FIGURE ASSUMES THE MONEY EARNS NOTHING. Every goal calculator in the industry
 *      asks for an expected rate and then quotes a figure to the rupee - change 12% to 9% and the
 *      answer moves by a third, so the precision is borrowed from an assumption nobody can check.
 *      The zero-return figure is arithmetic on two numbers the investor supplied, cannot be wrong,
 *      and is a genuine upper bound.
 *   4. THERE IS NO SEEDER. Every row here was typed by the investor. B-121 put ten permanent
 *      drift warnings on the landing page against sector targets nobody had chosen; a goal table
 *      with a helpful default would be the same bug with a longer horizon.
 *
 * Page-load requests, both DB-only (a page-load get() is ungated - only getOnDemand() has the
 * allowlist, Gotcha 39):
 *   GET /api/portfolio/goals        the goals, their splits, the evidence and the blend
 *   GET /api/portfolio/goals/rules  the rule table itself, so the rule can be read not trusted
 */

import { get, post, del, OfflineError } from './api.js';
import { initChrome, registerRefresh } from './nav.js';
import {
  el, section, collapse, withCount, withSummary, table, kpi, empty, skeleton, mount,
  unmeasured, costNote,
} from './ui.js';
import { inr, pct, num, shortDate, missing } from './format.js';

const view = document.getElementById('view');

let plan = null;   // /api/portfolio/goals
let rules = null;  // /api/portfolio/goals/rules
let busy = false;
let formError = null;

// ---------------------------------------------------------------------- the blend

function blendCards() {
  if (!plan || !plan.stated) return null;
  const b = plan.blend || {};
  if (!b.measured) {
    return el('div.info-box', {},
      el('h4', {}, 'No single figure for the portfolio'),
      el('p', {}, b.reason));
  }
  const gap = b.gapPp;
  return el('div.grid.kpis', {},
    kpi({
      label: 'Your goals imply',
      value: num(b.impliedEquityPct, 0) + '% in shares',
      sub: num(b.impliedDebtPct, 0) + '% in debt, weighted by each goal’s target amount',
      tone: 'neutral',
    }),
    kpi({
      label: 'You actually hold',
      value: b.heldMeasured ? num(b.heldEquityPct, 0) + '% in shares' : 'not measured',
      sub: b.heldMeasured
        ? num(b.heldDebtPct, 0) + '% in debt and cash, ' + num(b.heldOtherPct, 0) + '% elsewhere'
        : 'nothing in the portfolio could be valued today',
      tone: 'neutral',
    }),
    kpi({
      label: 'Difference',
      // POINTS, not percent. The gap between two percentages is a number of percentage points,
      // and a "%" sign on it invites the reader to divide - a figure quoted on a scale it was not
      // measured on (B-120's family).
      value: missing(gap)
        ? 'not measured'
        : (gap > 0 ? '+' : '') + num(gap, 0) + ' points',
      // Amber, never red, and only past the tolerance. A gap inside one month of market movement
      // is not a finding, and a row that fires every day and can never clear is what teaches a
      // reader to skip the section that matters (B-121, Gotcha 132).
      sub: missing(gap)
        ? 'nothing to compare'
        : (b.gapExceedsTolerance
          ? 'past the ' + num(b.tolerancePp, 0) + ' point mark this app calls a gap'
          : 'inside the ' + num(b.tolerancePp, 0) + ' point mark, so not called a gap'),
      tone: b.gapExceedsTolerance ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Goals on record',
      value: num(plan.goalCount, 0),
      sub: plan.totalTargetAmount
        ? 'totalling ' + inr(plan.totalTargetAmount)
        : 'no target amounts recorded',
      tone: 'neutral',
    }));
}

// ---------------------------------------------------------------------- one goal

/**
 * The split, drawn as two shares rather than as a score.
 *
 * Deliberately not a bar with a number on it: this is a decision the investor makes, and a
 * gauge invites it to be read as a grade.
 */
function mixCell(g) {
  return el('div', {},
    el('span.cell-main', {}, num(g.equityPct, 0) + '% shares'),
    el('span.cell-sub', {}, num(g.debtPct, 0) + '% debt'));
}

/**
 * The measured check on the rule.
 *
 * A floor on how bad it got, not a ceiling on how bad it can get - a fund that failed outright
 * was merged away and left the record. That direction rides on the cell rather than sitting in a
 * footnote, because a downside figure read the wrong way round is the most dangerous thing this
 * page could publish.
 */
function evidenceCell(g) {
  if (!g.evidenceMeasured || missing(g.worstOnMixPct)) {
    return unmeasured(g.evidenceReason
      || 'The fund archive cannot measure a stretch of this length yet.');
  }
  // A worst window that is a GAIN is the ordinary case for any horizon over about two years,
  // because this archive starts in April 2018 and holds one crash that recovered inside a year.
  // Left unlabelled, a column headed "worst stretch on record" showing +32% reads as "this cannot
  // lose money" - the most flattering failure this page could produce. So the caveat is a visible
  // sub-line and a paragraph under the table, never only a tooltip (SPEC 51, Gotcha 146).
  const positive = g.worstOnMixPct >= 0;
  const node = el('div', {},
    el('span.cell-main', {}, pct(g.worstOnMixPct, { digits: 1 })),
    el('span.cell-sub', {},
      positive
        ? 'no losing stretch this long on file'
        : (g.independentWindows
          ? num(g.independentWindows, 0) + ' independent stretches on file'
          : 'on this app’s archive')));
  if (g.worstOnMixNote) node.title = g.worstOnMixNote;
  return node;
}

function moneyCell(g) {
  if (missing(g.targetAmount)) {
    return el('span.faint', {}, 'no amount recorded');
  }
  return el('div', {},
    el('span.cell-main', {}, inr(g.targetAmount)),
    missing(g.earmarkedValue)
      ? el('span.cell-sub', {}, 'nothing recorded as set aside')
      : el('span.cell-sub', {}, inr(g.earmarkedValue) + ' set aside'
        + (g.earmarkedAsOf ? ' as of ' + shortDate(g.earmarkedAsOf) : '')));
}

function monthlyCell(g) {
  if (!g.requiredMonthlyMeasured) {
    return unmeasured(g.requiredMonthlyReason || 'Not enough recorded to work this out.');
  }
  const node = el('div', {},
    el('span.cell-main', {}, inr(g.requiredMonthlyAtZeroReturn)),
    el('span.cell-sub', {}, 'if it earns nothing'));
  node.title = 'The only figure on this page that is not a forecast. Anything the money earns is '
    + 'a reason the goal arrives early rather than the reason it arrives at all.';
  return node;
}

const GOAL_COLS = [
  {
    key: 'name',
    label: 'Goal',
    render: (g) => el('div', {},
      el('span.cell-main', {}, g.name),
      g.note ? el('span.cell-sub', {}, g.note) : null),
  },
  {
    key: 'monthsRemaining',
    label: 'When',
    align: 'r',
    render: (g) => el('div', {},
      el('span.cell-main', {}, shortDate(g.targetDate)),
      el('span.cell-sub', {}, g.horizonLabel + ' away')),
  },
  { key: 'equityPct', label: 'Split', align: 'r', render: mixCell },
  { key: 'targetAmount', label: 'Target', align: 'r', render: moneyCell },
  { key: 'requiredMonthlyAtZeroReturn', label: 'Per month', align: 'r', render: monthlyCell },
  { key: 'worstOnMixPct', label: 'Worst stretch on record', align: 'r', render: evidenceCell },
  {
    key: 'id',
    label: '',
    render: (g) => {
      const b = el('button.chip', { type: 'button', disabled: busy }, 'Remove');
      b.addEventListener('click', () => removeGoal(g.id, g.name));
      return b;
    },
  },
];

/** The per-goal reasoning, below the table rather than crammed into a tooltip. */
function reasons() {
  const goals = (plan && plan.goals) || [];
  if (!goals.length) return null;
  const measured = goals.filter((g) => g.evidenceMeasured && !missing(g.worstOnMixPct));
  const anyPositive = measured.some((g) => g.worstOnMixPct >= 0);
  return el('div', { style: 'margin-top:16px' },
    el('h4', { style: 'margin:0 0 6px' }, 'Why each split is what it is'),
    el('ul', {}, ...goals.map((g) => el('li', {},
      el('strong', {}, g.name + ': '), g.mixReason))),

    measured.length
      ? el('div', { style: 'margin-top:14px' },
        el('h4', { style: 'margin:0 0 6px' }, 'What the worst-stretch figure is, and is not'),
        el('ul', {}, ...measured.map((g) => el('li', {},
          el('strong', {}, g.name + ': '), g.worstOnMixNote))))
      : null,

    anyPositive
      ? el('div.info-box', { style: 'margin-top:14px' },
        el('h4', {}, 'Read the positive figures carefully'),
        el('p', {},
          'Where the worst stretch on record is a ',
          el('em', {}, 'gain'),
          ', that is a fact about the record and not about the risk. This app’s fund archive '
          + 'begins in April 2018. It contains one crash, in early 2020, which recovered inside a '
          + 'year — so no window longer than about two years finishes below where it started. '
          + 'Eight years is not long enough to have seen a decade that went nowhere, and India has '
          + 'had those.'))
      : null);
}

// ---------------------------------------------------------------------- the form

function addForm() {
  const name = el('input.field', {
    type: 'text', placeholder: 'What is it for? e.g. House deposit', maxlength: 120, disabled: busy,
  });
  const date = el('input.field', { type: 'date', disabled: busy });
  const amount = el('input.field', {
    type: 'number', placeholder: 'Target amount (optional)', min: '0', step: '1000', disabled: busy,
  });
  const saved = el('input.field', {
    type: 'number', placeholder: 'Already set aside (optional)', min: '0', step: '1000',
    disabled: busy,
  });
  const button = el('button.action', { disabled: busy }, busy ? 'Saving…' : 'Add goal');

  function submit() {
    const n = name.value.trim();
    if (!n) { name.focus(); return; }
    if (!date.value) { date.focus(); return; }
    addGoal({
      name: n,
      targetDate: date.value,
      targetAmount: amount.value ? Number(amount.value) : null,
      earmarkedValue: saved.value ? Number(saved.value) : null,
    });
  }
  button.addEventListener('click', submit);
  [name, amount, saved].forEach((i) =>
    i.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); }));

  return el('div', {},
    // The date is the required field and the reason is said out loud, because a form that simply
    // refuses is a form the reader argues with.
    costNote('A goal needs a name and a date. The date is the whole point: it is the only thing '
      + 'that decides how much of this money can sit somewhere it might be worth less on the day '
      + 'you need it. The two amounts are optional — without them the split still stands, and the '
      + 'monthly figure is left blank rather than guessed.'),
    el('div.form-row', {}, name, date, amount, saved, button),
    formError ? el('p', { style: 'color:var(--loss);margin:4px 0 0;font-size:13px' }, formError) : null);
}

// ---------------------------------------------------------------------- the rule table

function rulePanel() {
  if (!rules) return skeleton(2);
  const cols = [
    { key: 'horizonLabel', label: 'Money needed in' },
    {
      key: 'equityPct',
      label: 'Shares',
      align: 'r',
      render: (r) => el('span', {}, num(r.equityPct, 0) + '%'),
    },
    {
      key: 'debtPct',
      label: 'Debt',
      align: 'r',
      render: (r) => el('span', {}, num(r.debtPct, 0) + '%'),
    },
    { key: 'reason', label: 'Why', render: (r) => el('span.faint', {}, r.reason) },
  ];
  return el('div', {},
    el('div.info-box', {},
      el('h4', {}, 'This is a rule, not a measurement'),
      el('p', {}, rules.note),
      el('p', {},
        'The most equity any dated goal gets is ' + num(rules.maxEquityPct, 0) + '%, and nothing '
        + 'inside ' + num(rules.noEquityBelowMonths, 0) + ' months gets any. Both are judgements '
        + 'rather than measured thresholds, which is why they are printed here instead of buried.')),
    table(cols, rules.bands || [], { filter: false, sortKey: 'months', sortDir: 'asc' }),
    // No caveat list here on purpose: the page has one, at the bottom, and the same four
    // sentences printed twice on one screen is what teaches a reader to skip both.
  );
}

// ---------------------------------------------------------------------- render

function paint() {
  const nodes = [];
  const goals = (plan && plan.goals) || [];

  nodes.push(section('Where your goals and your portfolio stand',
    plan && plan.headline ? plan.headline : '',
    blendCards(),
    plan && plan.stated
      ? el('div.info-box', { style: 'margin-top:14px' },
        el('h4', {}, 'What this comparison can and cannot say'),
        el('p', {},
          'This app does not know which of your holdings belong to which goal, and does not try '
          + 'to work it out — inventing that link would be worse than leaving it blank. So the '
          + 'comparison is between the blend your goals imply and the blend of everything you '
          + 'own. That is a real reading and a limited one.'),
        el('p', {},
          el('strong', {}, 'Hybrid funds are counted as neither. '),
          'How much of one is in shares moves with its manager and is not published in the file '
          + 'this app reads, so splitting it would be a guess dressed as arithmetic.'))
      : null));

  nodes.push(withCount(section('Your goals',
    'One row per thing you are saving for. The split follows from the date and nothing else — no '
    + 'view on the market, no forecast, and no fund named anywhere.',
    addForm(),
    goals.length
      ? el('div', {},
        table(GOAL_COLS, goals, { filter: false, sortKey: 'monthsRemaining', sortDir: 'asc' }),
        reasons())
      : empty('No goals yet',
        'Add one above. A goal is something you are saving for with a date attached — a house '
        + 'deposit in four years, a child’s fees in eleven. Money with no date is not a goal for '
        + 'these purposes and this page does not apply to it.')),
  goals.length));

  nodes.push(withSummary(collapse(section('The rule this page applies',
    'Printed in full so you can disagree with it. A screen that applies a judgement should be '
    + 'able to show the judgement.',
    rulePanel()), { key: 'goals:rules' }),
  rules ? num(rules.maxEquityPct, 0) + '% cap' : null, { type: 'info' }));

  if (plan && (plan.caveats || []).length) {
    nodes.push(collapse(section('What this page cannot tell you',
      '',
      el('ul', {}, ...(plan.caveats || []).map((c) => el('li', {}, c)))),
    { key: 'goals:caveats' }));
  }

  mount(view, nodes);
}

// ---------------------------------------------------------------------- writes

async function addGoal(body) {
  busy = true;
  formError = null;
  paint();
  try {
    await post('/api/portfolio/goals', body);
    await reload();
  } catch (err) {
    // The server's own reason, verbatim. api.js post() throws it, and a refusal nobody can read
    // is the silent failure the guard exists to prevent (B-049).
    formError = String(err && err.message ? err.message : err);
    busy = false;
    paint();
    return;
  }
  busy = false;
  paint();
}

async function removeGoal(id, name) {
  // eslint-disable-next-line no-alert
  if (!window.confirm('Remove the goal "' + name + '"? The record is kept, not deleted.')) return;
  busy = true;
  paint();
  try {
    await del('/api/portfolio/goals/' + id);
    await reload();
  } catch (err) {
    formError = String(err && err.message ? err.message : err);
  }
  busy = false;
  paint();
}

// ---------------------------------------------------------------------- boot

async function reload() {
  // `.data` is not optional: api.js get() returns an envelope, and assigning it straight through
  // leaves every field undefined and renders the section shells with nothing inside (Gotcha 137).
  const [p, r] = await Promise.all([
    get('/api/portfolio/goals', { cache: false }).then((res) => res.data),
    get('/api/portfolio/goals/rules').then((res) => res.data),
  ]);
  plan = p;
  rules = r;
  paint();
}

async function boot() {
  view.replaceChildren(el('section.section', {},
    el('h2.section-title', {}, 'Loading goals…'), skeleton(3)));
  await initChrome();
  await reload();
}

boot().then(() => registerRefresh(boot)).catch((err) => {
  console.error(err);
  const msg = err instanceof OfflineError
    ? 'The dashboard could not reach the app. Is it running?'
    : String(err && err.message ? err.message : err);
  mount(view, section('Something went wrong', '', empty('Could not load goals', msg)));
});
