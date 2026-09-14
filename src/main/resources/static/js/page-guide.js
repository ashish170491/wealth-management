/**
 * "How to use this app" - the playbook page (SPEC 27.11).
 *
 * Two jobs. It explains the method in plain words, and it checks the investor's ACTUAL portfolio
 * against that method, so the advice is about their situation rather than a generic article they
 * would read once and forget.
 *
 * Written for someone who is not a market expert (SPEC 21): no jargon, no ratios, no statistics
 * vocabulary. "About 57 out of every 100 picks beat the market" instead of an information
 * coefficient. Every number that appears is either rupees, a count, or a plain percentage.
 *
 * DB-only on load. It reads holdings (already decorated server-side, SPEC 6.6) and the accuracy
 * summary, both verified DB-only by hand - a page-load get() is ungated (Gotcha 39).
 */

import { get } from './api.js';
import { initChrome } from './nav.js';
import { inr, pct } from './format.js';
import { el, section, kpi, alert, card, mount, badge, empty, unmeasured } from './ui.js';

const view = document.getElementById('view');

/**
 * Below this, a position is too small to change anything.
 *
 * Not a market rule - arithmetic. A holding that doubles adds (its weight) to the portfolio, so a
 * stake worth 2% of the total can triple and still leave the year roughly unchanged, while it can
 * still go to zero. The number is deliberately expressed to the reader in rupees, not as a weight.
 */
const MIN_USEFUL_WEIGHT_PERCENT = 4;

/**
 * The score this guide sends the reader to look at first.
 *
 * Not a threshold the engine knows about - the Screener has no score filter, only Grade - so it
 * is stated here once and used for every count on this page, rather than written into prose in
 * three places that could drift apart.
 */
const WORTH_A_LOOK_SCORE = 70;

// ------------------------------------------------------------------ live checks

/** One row of the "your portfolio right now" check: a plain finding plus what to do about it. */
function check({ ok, title, finding, todo }) {
  const node = el('div', {
    style: 'border-left:4px solid ' + (ok ? 'var(--profit)' : 'var(--warn)')
      + ';background:var(--surface);border-radius:var(--radius);padding:13px 16px;margin-bottom:10px',
  });
  node.append(el('div.row', { style: 'gap:9px;align-items:center;margin-bottom:5px' },
    badge(ok ? 'GOOD' : 'LOOK', { type: ok ? 'success' : 'warning', label: ok ? 'Looks fine' : 'Worth fixing' }),
    el('span', { style: 'font-weight:600' }, title)));
  node.append(el('div', { style: 'font-size:13.5px;margin-bottom:4px' }, finding));
  node.append(el('div.muted', { style: 'font-size:13px' }, 'What to do: ' + todo));
  return node;
}

function portfolioChecks(holdings) {
  if (!holdings || !holdings.length) {
    return empty('No holdings loaded yet',
      'Once your holdings sync from the broker, this section checks them against the method below.');
  }

  const total = holdings.reduce((s, h) => s + (h.currentValue || 0), 0);
  const count = holdings.length;
  const avg = count ? total / count : 0;
  const avgWeight = total ? (avg / total) * 100 : 0;

  const byValue = [...holdings].sort((a, b) => (b.currentValue || 0) - (a.currentValue || 0));
  const top5 = byValue.slice(0, 5).reduce((s, h) => s + (h.currentValue || 0), 0);
  const top5Pct = total ? (top5 / total) * 100 : 0;

  const dontAdd = holdings.filter((h) => ['AVOID', 'HOLD_OFF'].includes(h.buyTimingVerdict)).length;
  const flagged = holdings.filter((h) => h.signalNote).length;

  const out = [];

  out.push(check({
    ok: avgWeight >= MIN_USEFUL_WEIGHT_PERCENT,
    title: 'How spread out you are',
    finding: `You own ${count} stocks worth ${inr(total)} in total — about ${inr(avg)} in each. `
      + (avgWeight >= MIN_USEFUL_WEIGHT_PERCENT
        ? 'Each one is big enough to matter to your result.'
        : `A stock this size could double and still only add about ${pct(avgWeight, { signed: false })} `
          + 'to your total. It cannot help you much — but it can still go to zero.'),
    todo: avgWeight >= MIN_USEFUL_WEIGHT_PERCENT
      ? 'Nothing. Keep new money going into the ones you already believe in.'
      : 'Stop adding new names. Put new money into the stocks you already own and rate highly, '
        + 'until each holding is large enough to be worth the trouble of following it.',
  }));

  out.push(check({
    ok: dontAdd === 0,
    title: 'Which ones not to buy more of',
    finding: dontAdd === 0
      ? 'None of your holdings are flagged as a bad time to add.'
      : `${dontAdd} of your ${count} holdings say "avoid" or "hold off" in the `
        + '"Still a good time to buy?" column right now.',
    todo: dontAdd === 0
      ? 'Nothing right now.'
      : 'This is not a reason to sell them. It only means do not put fresh money in those ones '
        + 'this month. Open My Portfolio and read the reason next to each.',
  }));

  if (flagged > 0) {
    out.push(check({
      ok: false,
      title: 'Where the accounts look risky',
      finding: `${flagged} holding${flagged === 1 ? ' has' : 's have'} a warning on the company's `
        + 'own accounts, so the app has lowered its buy signal for you.',
      todo: 'Read the note on the Signal column. A good chart does not fix a problem in the books. '
        + 'Avoid adding to these; decide separately whether to keep what you own.',
    }));
  }

  out.push(check({
    ok: top5Pct <= 60,
    title: 'Your biggest bets',
    finding: `Your five largest holdings are ${pct(top5Pct, { signed: false })} of everything you own.`,
    todo: top5Pct <= 60
      ? 'This is a reasonable balance. No action.'
      : 'A bad year in one of these would hurt a lot. Add to smaller positions rather than these.',
  }));

  return out;
}

// ------------------------------------------------------------------ trust

/** Turns the measured record into one plain sentence a non-expert can act on. */
function trustSection(accuracy) {
  const rows = Array.isArray(accuracy) ? accuracy : [];
  const mb90 = rows.find((r) => r.source === 'MULTIBAGGER' && r.horizonDays === 90);
  const hit = mb90 && mb90.hitRatePercent != null ? Math.round(mb90.hitRatePercent) : null;
  const long = rows.filter((r) => r.horizonDays >= 180
    && (r.totalOutcomes || 0) > 0).length;

  const kpis = el('div.grid.kpis', {},
    kpi({
      label: 'Picks that beat the market',
      value: hit != null ? `${hit} out of 100` : 'not measured yet',
      sub: 'over three months',
      tone: 'neutral',
    }),
    kpi({
      label: 'How long it has kept score',
      value: 'about 5 months',
      sub: 'started April 2026',
      tone: 'neutral',
    }),
    kpi({
      label: 'Results for 6 months / 1 year',
      value: long > 0 ? 'available' : 'none yet',
      sub: 'the periods you actually invest over',
      tone: long > 0 ? 'neutral' : 'warn',
    }));

  return [
    kpis,
    alert({
      severity: 'WARNING',
      title: 'Read this before you trust a score',
      message: 'A coin toss would be right 50 times out of 100. The app is a little better than '
        + 'that — but "a little better" measured over five months is not proof. It has also not '
        + 'finished measuring a single 1-year result yet, and one year is the timeframe you '
        + 'actually care about. So use the scores to decide what to look at, not what to buy.',
    }),
  ];
}

// ------------------------------------------------------------------ static guidance

function threeThings() {
  const item = (n, title, body) => el('div', { style: 'margin-bottom:14px' },
    el('div', { style: 'font-weight:600;margin-bottom:3px' }, `${n}. ${title}`),
    el('div', { style: 'font-size:13.5px' }, body));

  return card(
    item(1, 'Hold winners past one year',
      'In India you pay 20% tax on a profit if you sell within a year, and 12.5% if you wait past '
      + 'one year. Waiting can hand you roughly 7% more of your own gain, and it is certain — '
      + 'unlike any prediction in this app. The Portfolio page shows how many days each purchase '
      + 'has left. Never sell a winner at day 300.'),
    item(2, 'Avoid the bad ones rather than finding the perfect one',
      'The app checks company accounts for warning signs — money not actually arriving, debt, '
      + 'shares being printed. This is the part of the app with the best track record. Dodging one '
      + 'disaster helps you more than finding one extra winner.'),
    item(3, 'Buy in three parts, not one',
      'The "How to buy" column gives three prices instead of one. Nobody can pick the bottom, and '
      + 'splitting the purchase means you are never all-in at the worst moment. It also stops you '
      + 'waiting forever for a dip that may never come.'),
  );
}

function routine() {
  const step = (title, body) => el('li', { style: 'margin-bottom:9px' },
    el('span', { style: 'font-weight:600' }, title + ' '), body);

  // The three headings need to read as headings, not as another list item: rendered plainly they
  // sat at the same indent as the numbers above them and looked like step 6.
  const heading = (text) => el('div', {
    style: 'font-weight:700;color:var(--navy);font-size:14px;margin:20px 0 8px;'
      + 'padding-bottom:5px;border-bottom:2px solid var(--navy-tint)',
  }, text);

  return [
    el('div', {
      style: 'font-weight:700;color:var(--navy);font-size:14px;margin:2px 0 8px;'
        + 'padding-bottom:5px;border-bottom:2px solid var(--navy-tint)',
    }, 'Once a month (about 30 minutes)'),
    el('ol', { style: 'margin:0 0 18px 20px;font-size:13.5px' },
      step('Add new savings.', 'This matters more than anything else on this page. Your monthly '
        + 'saving is likely to add more to your wealth than any stock pick will.'),
      step('Open the Screener and set one filter.', 'Set "Compounding" to "Compounders only", '
        + 'then sort by Score and look at 70 or more. Ignore the rest. That one filter is the '
        + 'difference between a share that has been going up and a business that has been '
        + 'compounding, and they are not the same list.'),
      step('Check two columns.', '"Still a good time to buy?" and whether the accounts are clean. '
        + 'Skip anything that says avoid.'),
      step('Open its stock page before you buy.', 'Scroll to "Has it actually compounded?" and '
        + '"What has management done with your money?". A high score sitting on a weak record is '
        + 'the one combination worth walking away from.'),
      step('Use the "How to buy" prices.', 'Put in the first of the three amounts. Not all of it.'),
      step('Prefer what you already own.', 'Topping up a good holding beats buying a 34th stock.')),

    heading('Once every three months (15 minutes)'),
    el('ol', { style: 'margin:0 0 18px 20px;font-size:13.5px' },
      step('Read the drift warnings.', 'On My Portfolio. If a company is getting worse, act on '
        + 'that — not on the share price falling.'),
      step('Check your holdings track records.', 'On each stock page. A company that compounded '
        + 'for years but had a weak last year is the earliest warning you get, and it usually '
        + 'arrives before the price moves.'),
      step('Check the tax dates.', 'Anything close to one year: wait.'),
      step('Do nothing else.', 'Most damage is done by reacting to price moves.')),

    heading('Never'),
    el('ul', { style: 'margin:0 0 0 20px;font-size:13.5px' },
      el('li', { style: 'margin-bottom:6px' }, 'Sell a good company just because it fell. That is '
        + 'usually when it is worth holding.'),
      el('li', { style: 'margin-bottom:6px' }, 'Buy something only because the app scored it '
        + 'highly. The score is a starting point, not a decision.'),
      el('li', { style: 'margin-bottom:6px' }, 'Check prices daily. It will make you trade, and trading will cost you money.'),
      el('li', {}, 'Apply for an IPO because everyone is talking about it. The IPO page explains the only '
        + 'honest reason to apply, and it is not the listing-day pop.')),
  ];
}

/**
 * What the 0-100 score is actually made of.
 *
 * Added 2026-09-06 with the seven-dimension correction: the screener and stock pages now draw
 * seven axes, not eight, and a reader looking at that shape had nothing explaining it. Named in
 * plain words rather than by their internal labels (SPEC 21) - "is it cheap" beats "valuation
 * dimension". The weighting sentence at the end matters more than the list: it is the honest
 * reason the score is a starting point and not a verdict (SPEC 40.2).
 */
function whatTheScoreIs() {
  const checks = [
    ['Is the price trending up?', 'Momentum. Whether the shares have been rising steadily.'],
    ['Is buying picking up?', 'Volume. Whether more people are buying than usual.'],
    ['Is it beating the market?', 'Relative strength. Whether it has done better than the Nifty.'],
    ['Is the chart pattern sound?', 'Price structure. Whether it keeps making higher lows rather than lurching.'],
    ['Is it cheap for what it earns?', 'Valuation. What the price already assumes about future growth.'],
    ['Are big investors buying?', 'Institutions. Whether funds have been increasing their stake.'],
    ['Are the accounts healthy?', 'Financial quality. Debt, whether profit turns into real cash, steady margins.'],
  ];

  const list = el('div', {}, checks.map(([q, b]) => el('div.row', {
    style: 'gap:10px;align-items:baseline;margin-bottom:8px',
  },
  el('span', { style: 'font-weight:600;min-width:210px;font-size:13.5px' }, q),
  el('span.muted', { style: 'font-size:13px;flex:1;min-width:200px' }, b))));

  return card(
    el('div', { style: 'font-size:13.5px;margin-bottom:12px' },
      'The score out of 100 is seven checks blended together. On the Screener and on any stock '
      + 'page you see them drawn as a seven-sided shape.'),
    list,
    el('div', {
      style: 'margin-top:14px;padding-top:11px;border-top:1px solid var(--rule);font-size:13.5px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'Two things worth knowing about it'),
    el('div', { style: 'margin-bottom:6px' },
      'A gap in the shape means ', el('strong', {}, 'the app could not measure that check'),
      ' for this company — usually because the filings it needs are not published. It does not '
      + 'mean the company scored zero. A stock with gaps is judged on what was measured.'),
    el('div', {},
      'The first four checks are all about the ', el('strong', {}, 'share price'),
      ', and together they are worth more than half the score. Only the last three are about the '
      + 'business. That is the main reason to treat a high score as a shortlist, not an answer — '
      + 'and the app is keeping score on whether a more business-weighted blend would do better.')),
    el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
      'An eighth check, on the stock’s sector, was removed in September 2026. It could only ever '
      + 'produce two different answers, and it showed no connection to how the stocks actually did.'),
  );
}

/**
 * The compounding lens (SPEC 41), explained where the reader will look for it.
 *
 * Deliberately separate from the score section above: the whole point of the lens is that it is
 * NOT part of the score, and putting it in the same card would undo that in the reader's head.
 */
function compoundingSection() {
  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:12px' },
      'A business grows your money at roughly ', el('strong', {}, 'what it earns on its own capital'),
      ', for as long as it can keep putting profits back in at that rate. A company earning 25% a '
      + 'year on its capital and reinvesting most of it roughly quadruples in a decade. That is '
      + 'what compounding means here, and it has almost nothing to do with the share price chart.'),
    el('div', { style: 'font-size:13.5px;margin-bottom:10px' },
      'So there is a separate ', el('strong', {}, '“Can it compound?”'),
      ' check on the Screener and on every stock page. Five questions about the business: does it '
      + 'earn well on the money in it, does the profit arrive as real cash, is growth paid for out '
      + 'of its own profits, are earnings steady, and are margins holding up. A stock earns the '
      + 'badge only if it passes every one that applies to it. On the latest run '
      + '22 of 288 stocks did.'),
    el('div', {
      style: 'padding:11px 13px;background:var(--surface-alt, #f6f7f9);border-radius:var(--radius);'
        + 'font-size:13px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'What this badge does and does not say'),
    el('div', { style: 'margin-bottom:6px' },
      'Every one of those five checks is read from ',
      el('strong', {}, 'the latest year of accounts only'),
      '. So read the badge as “this looks like a good business today”, not “this has '
      + 'compounded for years”. One good year is exactly what a steel or mining company shows '
      + 'at the top of its cycle, right before it turns.'),
    el('div', {},
      'That is why there is now a second section underneath it on every stock page, asking '
      + 'whether it actually ', el('strong', {}, 'has'),
      ' compounded. See the next part of this guide. Neither section changes the score, on '
      + 'purpose: they have not been checked against real returns yet.')),
  );
}

/**
 * The two multi-year sections (SPEC 42, SPEC 43), added to the guide 2026-09-06.
 *
 * The reader needs three things here and nothing else: what the new sections answer, why “not
 * enough years” is the usual answer today and will fix itself, and what to make of the two
 * panels disagreeing — which is the case most likely to send someone to a wrong conclusion,
 * because two adjacent badges saying opposite words look like a malfunction.
 */
function trackRecordSection() {
  const rows = [
    ['Have margins held up?',
      'Whether it kept its profit per rupee of sales, or competitors chipped it away.'],
    ['Does it earn every year?',
      'A business that loses money in the bad years spends the good ones recovering.'],
    ['Have earnings actually compounded?',
      'Whether profit grew, and grew at least as fast as sales rather than being bought by '
      + 'discounting.'],
    ['Did your slice survive?',
      'Whether they kept issuing new shares, which quietly shrinks your share of the profits.'],
    ['Has it earned well on capital, year after year?',
      'The strongest single piece of evidence there is. Needs the balance sheet, which older '
      + 'filings often leave out.'],
    ['Did it stay out of debt?',
      'Growth paid for with borrowing flatters the numbers until rates turn.'],
  ];

  const list = el('div', {}, rows.map(([q, b]) => el('div.row', {
    style: 'gap:10px;align-items:baseline;margin-bottom:8px',
  },
  // Fixed basis PLUS min-width:0. flex-basis alone is not enough: a flex item's automatic
  // minimum size keeps it from shrinking below its own content, so the longest question here
  // overflowed its basis and started its description at a different x from the other five.
  // A list the reader scans down has to have one left edge.
  el('span', { style: 'font-weight:600;flex:0 0 250px;min-width:0;font-size:13.5px' }, q),
  el('span.muted', { style: 'font-size:13px;flex:1;min-width:200px' }, b))));

  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:12px' },
      'Open any stock page and scroll past “Can it compound?”. There are two new sections.'),

    el('div', { style: 'font-weight:600;font-size:13.5px;margin-bottom:6px' },
      '1. “Has it actually compounded?”'),
    el('div', { style: 'font-size:13.5px;margin-bottom:10px' },
      'The same question, asked of ', el('strong', {}, 'every year of accounts on file'),
      ' instead of only the latest one. Six checks, and each shows how many years it held up in '
      + '— “6 of 8”, not a grade. That is deliberate: an average would let one '
      + 'spectacular year carry a whole decade, which is the exact trick a cyclical business '
      + 'plays on you.'),
    list,

    el('div', { style: 'font-weight:600;font-size:13.5px;margin:16px 0 6px' },
      '2. “What has management done with your money?”'),
    el('div', { style: 'font-size:13.5px;margin-bottom:10px' },
      'The profits a company keeps are yours — they simply reinvest them on your behalf. This '
      + 'is the record of what they did with them: whether they issued new shares and watered you '
      + 'down, how much they paid out versus kept, whether they kept building, whether growth was '
      + 'funded with borrowing, and ', el('strong', {}, 'what each extra rupee actually earned'),
      '. That last one is the question professional investors care most about, and until now this '
      + 'app could not answer it at all.'),
    el('div.muted', { style: 'font-size:13px;margin-bottom:14px' },
      'Bonus issues and share splits are set aside before any of this is worked out. Those hand '
      + 'you more shares without taking anything from you, so counting them as watering-down '
      + 'would be wrong — and it is an easy mistake to make.'),

    el('div', {
      style: 'padding:11px 13px;background:var(--info-bg);border-left:3px solid var(--info);'
        + 'border-radius:var(--radius);font-size:13px;margin-bottom:10px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' },
      'If the two sections seem to disagree, read that carefully'),
    el('div', {},
      'You may see “No” on the first section and “Held up” on the second, one '
      + 'above the other. That is not a fault. It means the company ', el('strong', {}, 'did'),
      ' compound for years and its most recent year is weaker. That gap is one of the most useful '
      + 'things on the page: it is what a business going off the boil looks like before the share '
      + 'price notices. The app adds a note explaining it whenever it happens.')),

    el('div', {
      style: 'padding:11px 13px;background:var(--surface-alt, #f6f7f9);border-radius:var(--radius);'
        + 'font-size:13px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' },
      'Most stocks will say “not enough years” for now'),
    el('div', {},
      'That is about the app, not the company. It needs at least five years of published accounts '
      + 'and today it has that for only a handful of stocks. It is now reading them in by itself, '
      + 'a few companies every weekday, so sections that say “not enough years” today '
      + 'should fill in over the coming weeks. ',
      el('strong', {}, '“Not measured” never means “bad”'),
      ' anywhere in this app — it means nobody has been able to look yet.')),
  );
}

/**
 * IPOs, for a reader who is about to be told about one by a friend (SPEC 45).
 *
 * Three things and nothing else: why the app refuses to say "apply", the two moments an IPO is
 * actually worth a look (before, with the prospectus; and six months after, when the sellers have
 * finished), and the one structural edge a small investor has - the shareholder quota - which
 * requires owning the parent BEFORE the prospectus is filed, so it has to be known in advance.
 */
/**
 * The five words the IPO tables use for where a listing is in its cycle.
 *
 * Written out here because they are the app's own vocabulary, not market usage — a reader who
 * has never seen "base forming" cannot guess it, and a filter chip is the worst possible place
 * to learn a word. Both tables that use these words link straight to this block.
 *
 * The order is deliberate: it runs from "do not judge this yet" to "this is the one worth
 * reading about", which is the order a listing actually travels in.
 */
function stageGlossary() {
  const row = (badgeNode, when, what) => el('div', {
    style: 'display:grid;grid-template-columns:190px 150px 1fr;gap:12px;padding:9px 0;'
      + 'border-bottom:1px solid var(--rule);font-size:13.5px;align-items:baseline',
  }, badgeNode, el('div.muted', {}, when), el('div', {}, what));

  return el('div', { id: 'ipo-stages', style: 'margin-top:18px' },
    el('div', { style: 'font-weight:600;margin-bottom:2px' }, 'The five stage words, in plain English'),
    el('div.muted', { style: 'font-size:13px;margin-bottom:10px' },
      'These appear on the IPOs page and in Discovery’s Recent Listings. They describe the '
      + 'share price against its own history since listing — nothing about the business.'),

    el('div', {
      style: 'display:grid;grid-template-columns:190px 150px 1fr;gap:12px;padding:0 0 6px;'
        + 'font-size:12px;font-weight:600;color:var(--ink-muted);border-bottom:2px solid var(--navy-tint)',
    }, el('div', {}, 'What it says'), el('div', {}, 'Roughly when'), el('div', {}, 'What it means for you')),

    row(badge('HYPE_WINDOW', { type: 'neutral', label: 'Too early — lock-in ahead' }),
      'First six months',
      'The people who owned this before it listed are not allowed to sell yet. Anchor investors '
      + 'are freed after 30 and 90 days, most early holders after six months — so the price you '
      + 'see has not met its real supply. The app refuses to judge anything here, however good '
      + 'the chart looks. There is nothing to do but wait.'),

    row(badge('WASHOUT', { type: 'warning', label: 'Below listing high' }),
      'After six months',
      'The excitement has drained away and the price is well below where it traded on day one. '
      + 'This is the normal fate of most new listings. It is not automatically a bargain — a '
      + 'company can be cheaper than its debut and still not worth owning — but it is where '
      + 'the honest prices tend to be.'),

    row(badge('RECOVERING', { type: 'info', label: 'Recovering' }),
      'After six months',
      'It fell and has started climbing back. Interesting, and the most easily misread of the '
      + 'five: a bounce is not a business improving. Use it as a prompt to go and read the '
      + 'accounts, not as evidence about them.'),

    row(badge('BASE_FORMING', { type: 'success', label: 'Base forming' }),
      'After six months',
      'It fell, stopped falling, and has traded quietly in a range instead of making new lows. '
      + 'Sellers who wanted out appear to be out. This is the one stage worth spending an hour '
      + 'on the company for — and it is rare: 1 of 238 listings on the day this was written.'),

    row(unmeasured('The app could not judge this one'),
      'Any time',
      'There is not enough price history from the broker to say anything. This is a gap in the '
      + 'app, never a verdict on the company — it is deliberately not lumped in with '
      + '“below listing high”, because “we did not look” and “it fell” are '
      + 'different facts.'),

    el('div.muted', { style: 'font-size:13px;margin-top:11px' },
      'None of these five is a reason to buy or avoid on its own. They tell you ',
      el('strong', {}, 'which question to ask next'),
      ' — the business columns beside them, and the prospectus, are what answer it.'));
}

/**
 * The Events page, explained — and the five words it uses that are the app's own, not the market's.
 *
 * The idea that has to land is the one that makes this feature different from a news feed: the
 * same event is good for one business and bad for another, so the app never scores a headline. It
 * records what moved, and looks up who is exposed. A reader who takes "headwind" as "sell" has
 * understood the opposite of what the page is for, so that sentence is the first thing here and
 * the last thing on the page itself.
 */
function macroSection() {
  const item = (title, body) => el('div', { style: 'margin-bottom:11px' },
    el('div', { style: 'font-weight:600;margin-bottom:2px' }, title),
    el('div', { style: 'font-size:13.5px' }, body));

  const word = (badgeNode, meaning) => el('div', {
    style: 'display:grid;grid-template-columns:150px 1fr;gap:12px;padding:9px 0;'
      + 'border-bottom:1px solid var(--rule);font-size:13.5px;align-items:baseline',
  }, badgeNode, el('div', {}, meaning));

  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:12px' },
      'Something happens every day that has nothing to do with any one company — the RBI moves '
      + 'rates, a tariff lands, oil jumps, the rupee slips, the rain fails. Each of those makes '
      + 'the next few quarters easier for some businesses and harder for others, ',
      el('strong', {}, 'at the same time'), '. A weaker rupee is bad news for the country and '
      + 'good news for an exporter. That is why the app never scores a headline as good or bad: '
      + 'it records only what moved and which way, then looks up which of your businesses are '
      + 'exposed to that particular thing, and how.'),

    item('The two halves, kept apart on purpose',
      'The event ledger records the event — the quantity, the direction, the size, the date — and '
      + 'is never allowed to name a company. A separate table, hand-written and readable at the '
      + 'bottom of the Events page, says which kinds of business each quantity helps or hurts and '
      + 'through what channel. What you see is those two joined. Keeping them apart is what lets '
      + 'you disagree with the reasoning instead of just the conclusion.'),

    item('It will not tell you to buy or sell',
      'A headwind is not a reason to sell and a tailwind is not a reason to buy. Their use is in '
      + 'reading the next set of results correctly: a good business having a hard quarter for a '
      + 'reason you can name is a very different thing from one whose thesis has broken. Nothing '
      + 'on that page changes any score anywhere in the app.'),

    item('Most days it has nothing to say, and it says so',
      'The usual honest answer is that nothing in the news reaches your portfolio, and the page '
      + 'prints that in as many words rather than going quiet. A screen that only appears when it '
      + 'has something alarming trains you to panic whenever you see it.'),

    item('It runs when you press the button',
      'Reading the news is the one thing on the dashboard that goes out to the internet, so it is '
      + 'not on a timer — nothing happens until you ask. The button tells you what it will do and '
      + 'what it costs before it does it.'),

    el('div', { id: 'macro-exposure', style: 'margin-top:18px' },
      el('div', { style: 'font-weight:600;margin-bottom:2px' }, 'The five words, in plain English'),
      el('div.muted', { style: 'font-size:13px;margin-bottom:10px' },
        'These appear in the Macro column on your portfolio, the screener, the watchlist and '
        + 'discovery, and on every stock page. The last two look similar and mean opposite '
        + 'things, which is the whole reason this list exists.'),

      el('div', {
        style: 'display:grid;grid-template-columns:150px 1fr;gap:12px;padding:0 0 6px;'
          + 'font-size:12px;font-weight:600;color:var(--ink-muted);border-bottom:2px solid var(--navy-tint)',
      }, el('div', {}, 'What it says'), el('div', {}, 'What it means for you')),

      word(badge('HEADWIND', { type: 'danger', label: 'Headwind' }),
        'Something recorded recently makes the next few quarters harder for this business — a '
        + 'costlier input, a weaker customer, a stricter rule. Expect it to show up in results '
        + 'before it shows up in the share price. Not a reason to sell.'),
      word(badge('TAILWIND', { type: 'success', label: 'Tailwind' }),
        'The same thing the other way round: conditions outside the company have turned in its '
        + 'favour. Not a reason to buy, and certainly not a reason to pay more.'),
      word(badge('MIXED', { type: 'warning', label: 'Both ways' }),
        'Recent events help this business in one place and hurt it in another — a refiner when '
        + 'crude moves, for instance. The app deliberately does not net them off into a single '
        + 'answer, because "no net effect" and "pulled hard in both directions" are not the same '
        + 'situation.'),
      word(badge('NOT_EXPOSED', { type: 'neutral', label: 'Not affected' }),
        'The app checked this business against every event on record and none of them applies. '
        + 'This is a finding, and on most days it is the right one.'),
      word(unmeasured('No rule in the exposure map for this kind of business yet'),
        'Different, and important: the app has no rule for this business at all, so it has not '
        + 'looked. That is a gap in its own table, never an all-clear and never a verdict on the '
        + 'company.')),

    el('div.muted', { style: 'font-size:13px;margin-top:14px' },
      'One more thing worth knowing. Without a language model configured, the headlines are read '
      + 'by simple keyword rules, and they make mistakes — an opinion column about what a rate '
      + 'rise would mean can be recorded as a rate rise. Anything that looks wrong can be marked '
      + '"Not news" on the Events page: it stops counting against your stocks and stays on the '
      + 'record, because a ledger that quietly deleted its mistakes could never be judged.'));
}

function ipoSection() {
  const item = (title, body) => el('div', { style: 'margin-bottom:11px' },
    el('div', { style: 'font-weight:600;margin-bottom:2px' }, title),
    el('div', { style: 'font-size:13.5px' }, body));

  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:12px' },
      'A new listing is a company you know less about than any stock on the Screener: no filings as a '
      + 'listed company, no years of accounts, no record of what management does with your money. '
      + 'Most of them trade below their first-day price a year later. The ones that go on to compound '
      + 'are found ', el('strong', {}, 'after'), ' the excitement, not during it — which is why the '
      + 'IPO page in the menu deliberately judges nothing younger than six months.'),

    item('Before it lists: read the structure, then the prospectus',
      'The app cannot check the business, so it reads the offer instead. "Where the money goes" tells '
      + 'you how much of what you pay reaches the company rather than the people selling; "Times '
      + 'subscribed" tells you, once bidding has closed, whether the big institutions — who read the '
      + 'whole prospectus and met the management — wanted it. A favourable structure means spend thirty '
      + 'minutes on the six prospectus points the page lists. It never means apply.'),

    item('The one test',
      'Would you buy this company at the top of its price band on listing day and hold it for five '
      + 'years? If yes, apply for one lot. If you are applying for the first-day jump, you are buying a '
      + 'lottery ticket: in the popular issues about one applicant in thirty gets a lot, the prize is a '
      + 'few thousand rupees, and the issues where everyone gets allotted are the ones nobody wanted.'),

    item('Which category, and how much',
      'You are a retail applicant: up to ₹2 lakh, bidding at cut-off. When an issue is oversubscribed '
      + 'every retail applicant has the same chance of one lot no matter how many they asked for, so '
      + 'the efficient bid is one lot per person — one in your account, one in each family member’s '
      + 'own demat and bank account. Applying for ten lots in one account only blocks more money for '
      + 'the same odds. The larger categories need ₹2 lakh or ₹10 lakh per application and are more '
      + 'crowded, not less.'),

    item('The shareholder quota is the only real edge you have — and it needs planning',
      'When a listed company floats a subsidiary, part of the issue is often reserved for people who '
      + 'already own the parent. The reserved pool is usually far less oversubscribed, so the odds of '
      + 'an allotment are several times better, and you may apply in this quota and in retail with the '
      + 'same PAN. The catch: you must hold at least one share of the parent on the day the prospectus '
      + 'is filed, about a week before the issue opens. The IPO page flags the quota on each issue; the '
      + 'prospectus names the parent. If you hear a big group is listing a subsidiary, that is the moment '
      + 'to own one share of the parent, not the day the issue opens.'),

    item('After it lists: wait for the sellers to finish',
      'Anchor investors may sell after 30 and 90 days; early investors and part of the promoters’ '
      + 'holding after six months. The IPO page shows those dates for every recent listing. The setup '
      + 'worth researching is a stock that is past six months, back above its listing-day high, and '
      + 'making higher lows — hype gone, early sellers done, strength returning. Use the Analyse '
      + 'button there to pull its first results as a listed company and a screening score, then judge '
      + 'it like any other stock: quality first, then the "how to buy" ladder.'),

    el('div.muted', { style: 'font-size:12.5px;margin-top:6px' },
      'SME (EMERGE) listings are left out of the app entirely. They trade thinly, disclose less, and are '
      + 'where most of the manipulation happens. That is a decision, not a gap. ',
      el('a', { href: 'ipo.html' }, 'Open the IPO page →')),

    stageGlossary(),
  );
}

function ignoreList() {
  const rows = [
    ['Anything promising a quick trade', 'The app used to scan for daily breakouts, sector turns '
      + 'and options signals. All of it was removed in September 2026 because none of it was ever '
      + 'shown to help, and one part had been measured as slightly worse than useless. Nothing '
      + 'here is meant to be acted on the same day.'],
    ['The raw "Signal" word on its own', 'It is based on the share price chart only. It has never '
      + 'said "sell" about anything you own, which tells you it is not a full opinion.'],
    ['New stocks the app discovers by itself', 'The way it finds them partly repeats the way it '
      + 'scores them, so they look better than they are. They are being watched, not recommended.'],
  ];
  return el('div', {}, rows.map(([t, b]) => el('div', { style: 'margin-bottom:11px' },
    el('div', { style: 'font-weight:600;margin-bottom:2px' }, t),
    el('div.muted', { style: 'font-size:13px' }, b))));
}

/**
 * Which page answers which question (SPEC 27.2).
 *
 * Added 2026-09-09 because the investor asked what the difference between the Screener and the
 * Discovery page actually is - a question this guide could not answer, since it named only the
 * Screener and never mentioned Discovery at all. Both pages read the SAME screening run
 * (/api/dashboard/screener); the difference is the question asked of it, and that is the one
 * thing the reader needs to know.
 *
 * The live counts carry the argument rather than the prose: "70 or more leaves about a hundred
 * names, and asking for a compounding record as well leaves about fifteen" is the whole reason to
 * touch that filter. They are derived from the run being described and never written down as
 * literals, so they cannot go stale against the page they send the reader to (Gotcha 98).
 */
function pageMap(screener) {
  const rows = (screener && screener.scores) || [];
  const high = rows.filter((r) => (r.compositeScore || 0) >= WORTH_A_LOOK_SCORE);
  const both = high.filter((r) => r.compounding === 'COMPOUNDER');
  const top = rows.filter((r) => r.verdict === 'STRONG_MULTIBAGGER');
  const topCompounders = top.filter((r) => r.compounding === 'COMPOUNDER');
  const topNo = top.filter((r) => r.compounding === 'NO');

  const page = (name, question, use) => el('div', {
    style: 'margin-bottom:13px;padding-bottom:11px;border-bottom:1px solid var(--rule)',
  },
  el('div', { style: 'font-weight:700;color:var(--navy);margin-bottom:2px' }, name),
  el('div', { style: 'font-size:13.5px;margin-bottom:3px' }, question),
  el('div.muted', { style: 'font-size:13px' }, use));

  const pages = el('div', {},
    page('Screener',
      'Of the companies the app follows, which look strongest today?',
      'This is the page you build a shortlist from. Every stock it covers, scored out of 100, '
      + 'sortable by any column.'),
    page('Discovery',
      'The same companies as the Screener, asked three different questions - plus one list that '
      + 'is genuinely new.',
      'Could I actually buy it without moving the price? Are the people who run it buying it? Is '
      + 'anything here still overlooked? The fourth part is a list of companies the app found by '
      + 'itself in the wider market - those are being watched, not recommended, and the last '
      + 'section of this guide explains why.'),
    page('Watchlist',
      'The stocks you noted - how have they done since, and is it still a good time?',
      'Nothing is bought or sold here. It is the record of what you were interested in and what '
      + 'happened next, which is the only honest way to find out whether your own judgement is '
      + 'any good.'),
    page('A stock\u2019s own page',
      'Everything the app knows about one company, including the two long-term records.',
      'The compounding record and what management did with your money appear in full only here. '
      + 'Nothing should be bought without opening this page first.'));

  const counts = rows.length
    ? el('div', {
      style: 'padding:12px 14px;background:var(--info-bg);border-left:3px solid var(--info);'
        + 'border-radius:var(--radius);font-size:13.5px;line-height:1.6;margin-top:4px',
    },
    el('div', { style: 'font-weight:700;margin-bottom:5px' },
      'The most useful control on the Screener, and the one most people never touch'),
    el('div', { style: 'margin-bottom:6px' },
      'Set ', el('strong', {}, 'Compounding'), ' to ', el('strong', {}, '\u201cCompounders only\u201d'),
      ', then click the ', el('strong', {}, 'Score'), ' column to sort highest first. On the '
      + 'latest run that takes ', el('strong', {}, String(rows.length) + ' companies'), ' down to ',
      el('strong', {}, String(high.length)), ' scoring ' + WORTH_A_LOOK_SCORE + ' or more, and then to ',
      el('strong', {}, String(both.length) + (both.length === 1 ? ' name' : ' names')),
      ' that also have a compounding record behind them.'),
    el('div.muted', { style: 'font-size:13px' },
      'That is a shortlist you can read in an evening, and it is filtered on the business rather '
      + 'than on the share price.'))
    : null;

  const trap = top.length
    ? el('div', {
      style: 'padding:12px 14px;background:var(--warn-bg);border-left:3px solid var(--warn);'
        + 'border-radius:var(--radius);font-size:13.5px;line-height:1.6;margin-top:10px',
    },
    el('div', { style: 'font-weight:700;margin-bottom:5px' }, 'A top score is not a good business'),
    el('div', {},
      'On the latest run the app gave its highest verdict to ',
      el('strong', {}, String(top.length) + ' companies'), '. Of those, ',
      el('strong', {}, String(topCompounders.length)),
      ' have actually been compounding'
      + (topNo.length ? ', and ' + String(topNo.length) + ' fail that check outright' : ''),
      '. The score is more than half made of share-price behaviour, so it finds what is going up '
      + 'now - which is not the same question as what will still be worth owning in ten years.'))
    : null;

  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:14px' },
      'The Screener and Discovery pages read ', el('strong', {}, 'the same screening run'),
      '. They are not two different lists of stocks. The difference is only which question is '
      + 'being asked of it - so if you are looking for something new to buy, the Screener is '
      + 'where you decide, and Discovery is where you check whether you could actually buy it.'),
    pages,
    counts,
    trap);
}

// ------------------------------------------------------------------ render

async function render() {
  // The screening run is read here for the same reason the holdings are: so the page map can
  // quote what the filters leave TODAY rather than a number written into the prose months ago.
  // DB-only and dashboard-safe - it is the identical call the Screener and Discovery pages make
  // on load (Gotcha 39: a page-load get() is ungated, so this was checked by hand).
  const [holdings, accuracy, screener] = await Promise.all([
    get('/api/trading/holdings', { fallback: [] }).then((r) => r.data).catch(() => []),
    get('/api/accuracy/summary', { fallback: [] }).then((r) => r.data).catch(() => []),
    get('/api/dashboard/screener', { fallback: null }).then((r) => r.data).catch(() => null),
  ]);

  mount(view,
    el('div', { style: 'margin-bottom:6px' },
      el('h1', { style: 'font-size:23px;color:var(--navy)' }, 'How to use this app'),
      el('div.muted', { style: 'font-size:13.5px' },
        'A short, plain guide — and a check of your own portfolio against it.')),

    section('The short version',
      'This app is best at keeping you out of trouble and keeping you patient. It is not yet good '
      + 'enough at picking winners to be trusted on its own.',
      card(el('div', { style: 'font-size:14px;line-height:1.65' },
        'Use it to ', el('strong', {}, 'narrow down what to look at'), ', to ',
        el('strong', {}, 'avoid companies with problems in their accounts'), ', and to ',
        el('strong', {}, 'hold your winners past one year for the lower tax'), '. ',
        'Those three things are reliable today. Picking the next multibagger is not.'))),

    section('Your portfolio right now',
      'The same advice, but measured against what you actually own today.',
      portfolioChecks(holdings)),

    section('The three things that actually build wealth here',
      'In order of how certain they are. The first one is arithmetic, not a prediction.',
      threeThings()),

    section('How much to trust the scores',
      'The app keeps score of its own picks. Here is that record, in plain terms.',
      trustSection(accuracy)),

    section('What the score is actually made of',
      'Seven checks, in plain words. Worth reading once so the shape on the Screener means '
      + 'something to you.',
      whatTheScoreIs()),

    section('How to spot a business that can compound',
      'The question this app exists for. Worth reading once — it is the difference between owning '
      + 'a good business and owning a share that has been going up.',
      compoundingSection()),

    section('Has it actually compounded, and what did management do with your money?',
      'Two newer sections on every stock page. The first is the strongest evidence this app can '
      + 'give you; the second is the half of the judgement it never had.',
      trackRecordSection()),

    section('Which page to use, and what for',
      'Four pages do most of the work. The Screener and Discovery show the same screening run, '
      + 'asked different questions - which is the thing most easily misunderstood about them.',
      pageMap(screener)),

    section('What to do, and when',
      'A simple routine. Doing less, on a schedule, beats doing more whenever you feel like it.',
      routine()),

    section('How to know the numbers are current and not quietly broken',
      'There is a Data Health page in the top menu. It is the app checking its own data.',
      dataHealthSection()),

    section('New listings (IPOs)',
      'How to think about a company that has just listed, or is about to. The app helps you read the offer '
      + 'and the calendar; it will not tell you to apply.',
      ipoSection()),

    section('Events outside the company, and which of your stocks they touch',
      'Rate decisions, tariffs, the oil price, the rupee, the monsoon. What the Events page does '
      + 'with them, and the two words it refuses to turn them into.',
      macroSection()),

    section('What to ignore',
      'Parts of the app that are not ready to act on. They are shown for honesty, not for use.',
      ignoreList()),
  );
}

/**
 * The Data Health page, explained for a reader who is not going to think about schedulers.
 *
 * <p>The one idea worth getting across is that the failure mode here is not a wrong number
 * but a missing one drawn as though it were real, and that an all-clear is not a promise the
 * figures match the filings. Everything else is detail they can read on the page itself.
 */
function dataHealthSection() {
  return card(
    el('div', { style: 'font-size:13.5px;line-height:1.65;margin-bottom:10px' },
      'Open ', el('strong', {}, 'Data Health'), ' in the menu. It runs three checks every time '
      + 'you load it, and lists all of them — including the ones that passed, so you can '
      + 'see what was actually looked at rather than being told everything is fine.'),

    el('div', { style: 'font-size:13.5px;line-height:1.7;margin-bottom:12px' },
      el('div', {}, el('strong', {}, 'Is everything up to date?'),
        ' Each part of the app writes at its own time on its own days. The page knows the '
        + 'timetable, so it can tell you Saturday’s scores are the right answer on a '
        + 'Sunday — which is easy to misread as something being broken.'),
      el('div', { style: 'margin-top:6px' }, el('strong', {}, 'Was each thing actually measured?'),
        ' This is the important one. The way this app has gone wrong in the past is not a wrong '
        + 'number — it is a number that was never worked out at all, shown as if it had '
        + 'been. That has happened three times and each one ran for months looking perfectly '
        + 'normal. The page counts how many companies each measure was taken on, and whether '
        + 'the answer varied between them. A figure that is identical for every company is '
        + 'telling you nothing, however sensible it looks.'),
      el('div', { style: 'margin-top:6px' }, el('strong', {}, 'Is there enough history?'),
        ' How many companies have enough years of accounts for the long-term sections, and '
        + 'whether the app is still reading more in.')),

    el('div', {
      style: 'padding:11px 13px;background:var(--warn-bg);border-left:3px solid var(--warn);'
        + 'border-radius:var(--radius);font-size:13px',
    },
    el('div', { style: 'font-weight:600;margin-bottom:4px' }, 'What a clean result does not mean'),
    el('div', {},
      'It means every check the app can run on itself came back clean. It cannot tell you a '
      + 'figure matches what the company actually published — nothing in the app re-reads '
      + 'the filings to compare. Once a quarter, take one holding you care about, open its stock '
      + 'page beside that company’s annual report, and check two or three numbers by hand. '
      + 'That is the one check only you can do, and the page says so itself.')),
  );
}

/**
 * Jump to the block named in the URL, once it exists.
 *
 * The browser resolves `#ipo-stages` while the page is still an empty <main> — this page builds
 * itself from three fetches — so by the time the target is in the DOM the browser has long since
 * given up. Without this, every link into the guide lands the reader at the top of a 6,800px
 * page and leaves them to find the block themselves, which is exactly the friction the link was
 * added to remove. The brief highlight is there because arriving mid-document with no visual
 * anchor reads as a mis-scroll.
 */
function jumpToHash() {
  const id = (window.location.hash || '').slice(1);
  if (!id) return;
  const target = document.getElementById(id);
  if (!target) return;
  target.scrollIntoView({ block: 'start' });
  target.classList.add('jump-target');
  setTimeout(() => target.classList.remove('jump-target'), 2600);
}

await initChrome();
render().then(jumpToHash).catch((e) => mount(view, alert({
  severity: 'URGENT',
  title: 'Could not load the guide',
  message: String((e && e.message) || e),
})));
