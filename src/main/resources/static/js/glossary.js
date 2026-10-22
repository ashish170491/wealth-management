/**
 * Plain-English glosses — SPEC section 21 rules 1 and 2, mechanized.
 *
 * The investor is not a stock-market expert, so every technical term gets an explanation on
 * first use in a section. Doing that by hand on every screen guarantees it decays, so the
 * terms live here once and `glossify()` applies them automatically: first occurrence per
 * section gets a dotted underline and a native tooltip, later ones are left alone.
 *
 * Adding a term here makes it explained everywhere. That is the point.
 */

/** term -> one-sentence, jargon-free explanation. Keep these SHORT; they render in a tooltip. */
export const TERMS = {
  HHI: 'Concentration score from 0 to 10,000. Higher means your money is packed into fewer stocks or sectors. It cannot see positions that are too small to matter.',
  'time-weighted': 'Return with your deposits and withdrawals removed, so it measures what the holdings did, and can be compared with an index.',
  TWR: 'Time-Weighted Return. Return with your deposits and withdrawals removed, so it measures what the holdings did and can be compared with an index.',
  drawdown: 'How far the portfolio has fallen from its highest point. The deepest one tells you what a bad stretch has felt like so far.',
  annualised: 'The return restated as a per-year rate, so a 7-month figure and a 3-year figure can be compared.',
  'lock-in': 'A period after a listing during which certain shareholders are not allowed to sell. When it ends, more shares can hit the market.',
  IC: 'Information Coefficient. Measures whether a higher score actually led to a better return. Above +0.10 is considered a genuinely useful signal.',
  'Information Coefficient': 'Measures whether a higher score actually led to a better return. Above +0.10 is considered a genuinely useful signal.',
  STCG: 'Short-Term Capital Gains tax. Applies when you sell shares held under 365 days, at 20%.',
  LTCG: 'Long-Term Capital Gains tax. Applies when you sell shares held over 365 days, at a lower rate with an annual exemption.',
  EMA: 'Exponential Moving Average. The average price over a period, weighted towards recent days, used to read the trend.',
  RSI: 'Relative Strength Index, 0 to 100. Above 70 suggests a stock may be overbought, below 30 oversold.',
  ATR: 'Average True Range. How much the price typically moves in a day, used to size sensible stop-losses.',
  PEG: 'Price/Earnings-to-Growth. The P/E divided by the growth rate. Under 1 can mean growth you are not paying much for.',
  'P/E': 'Price-to-Earnings. The share price divided by earnings per share. Higher means the market expects more growth.',
  PE: 'Price-to-Earnings. The share price divided by earnings per share. Higher means the market expects more growth.',
  ROCE: 'Return on Capital Employed. How much profit the business makes from all the money invested in it. Above 20% is strong.',
  ROE: 'Return on Equity. How much profit the business makes from shareholders money. Above 18% is strong.',
  ROA: 'Return on Assets. Profit as a share of total assets. The headline efficiency measure for banks; above 1.5% is strong.',
  'D/E': 'Debt-to-Equity. How much the company has borrowed against shareholders money. Under 0.3 is conservative, over 2 is risky.',
  DCF: 'Discounted Cash Flow. Works out what growth rate the current share price is already assuming.',
  'reverse-DCF': 'Takes the price as given and solves for the growth rate needed to justify it, then compares that to actual past growth.',
  EPS: 'Earnings per share. The company’s profit divided by its number of shares. Shown here with bonus issues and splits divided out, so different years can be compared.',
  'normalised EPS': 'The latest year’s earnings per share, lowered to the trend’s value only if that year looks like a clear one-off peak. A bad year is never lifted.',
  'standard error': 'How much room for error a fitted growth rate has. A big one means the past record is too uneven to say much about its own trend.',
  'R-squared': 'How much of the up-and-down in past earnings a straight-line trend explains, from 0 to 1. Near 0 means the trend explains very little.',
  'reference multiple': 'A price-to-earnings figure used only as a column in a what-if table. It is not what any business deserves and not a view on what the market will pay.',
  'hurdle rate': 'The yearly return a price is assumed to have earned. Here 12%, price return only.',
  'required growth': 'The yearly growth in earnings a price would need, at a stated multiple, for the price to have earned the hurdle rate. A what-would-have-to-be-true figure, not a prediction.',
  VWAP: 'Volume Weighted Average Price. The average price paid through the day, weighted by how much traded at each level.',
  'FII': 'Foreign Institutional Investors. Overseas funds buying and selling Indian shares. Large flows move the market.',
  'DII': 'Domestic Institutional Investors. Indian mutual funds and insurers. Often buy when foreign funds sell.',
  'excess return': 'How much better or worse a pick did than the Nifty 50 over the same period. This is the number that matters.',
  'hit rate': 'The share of picks that ended up profitable.',
  'drift': 'How far your actual holding has moved away from the target percentage you set for it.',
  'percentile rank': 'Where this stock sits against everything else screened. 90 means it scored better than 90% of them.',
  'composite score': 'One 0-100 score blending all eight scoring dimensions.',
  coverage: 'The share of screened stocks a signal could actually be calculated for. A signal with low coverage tells you little, however good its other numbers look.',
  'scoring engine': 'The exact set of weights and settings used to produce a score. Scores made by different versions are not directly comparable.',
  'quality score': 'Is this a business worth owning for years? The 0-100 composite from the screening — earnings, balance sheet, cash flow, ownership. Slow-moving by design.',
  'timing score': 'Is today a sensible day to pay this price? A 0-100 read of the trend, RSI and momentum from the daily chart. Fast-moving by design.',
  'return since added': 'How far the price has moved since the day you put the stock on your list. Not shown for stocks seeded from the config file, because no price was recorded then.',
  pullback: 'A short dip in a rising stock. Buying on a dip, near its 20- or 50-day average, is usually safer than chasing it after a run-up.',
  'wait for a dip': 'The business is fine but the price has run ahead of itself — RSI is high or it is stretched above its 50-day average. Wait for it to come back a little.',
  accumulate: 'Buy gradually, in small tranches over weeks, rather than all at once. Used when the business is good but there is no clear entry trigger.',
  'multibagger': 'A stock that could multiply your money several times over years, not weeks.',
  'thesis drift': 'Your reason for owning a stock weakening over time, measured as its score falling.',
  'stop-loss': 'A price at which you would sell to cap a loss.',
  'support': 'A price level where buyers have stepped in before, so falls have tended to pause.',
  'resistance': 'A price level where sellers have stepped in before, so rises have tended to stall.',
  'promoter': 'The founding owners of the company. Them buying more is usually a good sign.',
  'pledge': 'Shares the promoters have put up as loan collateral. A high figure is a risk flag.',
  'delivery %': 'The share of trading that was real buying, not same-day speculation. Higher means genuine accumulation.',
  'under-discovery': 'How overlooked a stock still is: small, barely owned by big institutions, and little written about it. The room a re-rating has to happen in.',
  'under the radar': 'Passes the quality bar but big investors have not arrived yet.',
  buyability: 'Whether you could actually build a position without moving the price yourself.',
  ADV: 'Average Daily traded Value. The rupees of this stock that change hands on a typical day.',
  'circuit risk': 'Days the stock was frozen at its price limit, when nobody could buy or sell it at all.',
  THIN: 'Trades so little that building or exiting a position would move the price against you.',
  'insider pulse': 'Whether company insiders — promoters, directors, senior management — have been buying or selling their own shares on the open market.',
  'open-market purchase': 'An insider buying shares with their own money at the market price. Unlike a share grant or a pledge, this is a real bet.',
  'inter-se transfer': 'Shares moved between promoters. No new money is invested, so it says nothing about conviction.',
  ESOP: 'Shares granted to employees as pay. Not a purchase decision, so it carries no signal.',
  SAST: 'Rules requiring anyone building a large stake to disclose it publicly.',
  'dynamic universe': 'Stocks the system found by itself, outside the hand-picked list it normally screens.',
  'coarse scan': 'A cheap first pass over the whole market using price and volume only, to decide what deserves a proper look.',
  'observation mode': 'The feature runs and records what it would have done, but does not yet change anything.',
  'post-IPO base': 'A recently listed stock that has been through its hype phase, held its ground, and started building higher lows.',
  'interest coverage': 'How many times over the company profit covers its interest bill. Under 1.5 is stressed.',
  'cash conversion': 'How much of the reported profit shows up as actual cash. Well under 1 raises accounting questions.',
  'offer for sale': 'Shares sold in an IPO by people who already own them. The money goes to those sellers, not into the company.',
  'fresh issue': 'New shares created in an IPO. This is the money that actually goes into the business.',
  'cut-off': 'Bidding at whatever price the IPO ends up discovering. Retail applicants may do this; larger applicants must name a price.',
  'lock-in': 'A period after listing during which certain holders are not allowed to sell. When it ends, their shares can hit the market.',
  'anchor investors': 'Large institutions allotted shares the day before an IPO opens, at the issue price, with a lock-in of 30 to 90 days.',
  QIB: 'Qualified Institutional Buyers — mutual funds, insurers, foreign funds. They get half of most IPOs and read the full prospectus first.',
  ASBA: 'The bank-blocked payment route for IPO applications. Your money stays in your account until allotment.',
  'basis of allotment': 'How the registrar splits the shares when an IPO is oversubscribed. For retail it is a lottery for one lot per applicant.',
  'hype window': 'The first six months after a listing, before the early investors are allowed to sell. The app judges nothing this young.',
  'HHI (concentration)': 'Concentration score from 0 to 10,000. Higher means your money is packed into fewer stocks or sectors.',
  'market cap': 'Market capitalisation. The share price times the number of shares — what the whole company is valued at today, in crore.',
  YoY: 'Year-on-year. The latest quarter against the same quarter a year earlier, which strips out seasonal swings.',
  'red flag': 'A pattern in the accounts that has preceded trouble elsewhere: dilution, receivables outrunning sales, profit that never turns into cash, an auditor problem. "Not checked" means the screen could not run, not that the accounts are clean.',

  // Macro and geopolitical exposure (SPEC 48). Deliberately no bare 'Fed' or 'crude' entry:
  // short words match inside longer ones and would underline half the page.
  headwind: 'Something outside the company that makes the next few quarters harder for it — a costlier input, a weaker customer, a stricter rule. Not a reason to sell.',
  tailwind: 'Something outside the company that makes the next few quarters easier for it. Not a reason to buy.',
  'macro event': 'Something that moved which affects whole industries rather than one company: a rate decision, a tariff, the oil price, the rupee, the monsoon.',
  'exposure map': 'The app’s hand-written table of which kinds of business are helped or hurt when each of these quantities rises, and through what channel. You can read it on the Events page.',
  'not affected': 'The app checked this business against every recent event and none of them applies. That is a finding — different from having no rule for it at all.',
  'repo rate': 'The rate at which the RBI lends to banks. Raising it makes borrowing dearer across the economy.',
  MPC: 'The RBI’s Monetary Policy Committee, which sets the repo rate roughly every two months on published dates.',
  USDINR: 'The rupee against the US dollar. When it rises the rupee has weakened, which helps exporters and hurts importers.',
  tariff: 'A tax a country puts on imported goods, which makes the exporter’s products dearer in that market.',
  'monsoon deficit': 'Rainfall running below the long-period average, which cuts rural incomes and the spending that depends on them.',
  'scheduled event': 'Something the market knew was coming — a policy meeting, a budget, a data release.',
  'surprise event': 'Something nobody had dated in advance.',
  'safeguard duty': 'A temporary import tax protecting domestic producers from a surge of cheap imports.',
  'universe shift': 'How much the typical screened stock moved between two runs. A stock\'s own change is read against this, so a market-wide move is not mistaken for news about the company.',
  'expectation gap': 'The growth rate today\'s price already assumes, minus the growth the company has actually delivered. A large positive gap means the price is banking on an acceleration.',

  // SPEC 52 - the mutual-fund vocabulary. Several of these are the app's own words rather than
  // market usage (rolling window, not comparable, survivorship), and a badge is the worst place
  // to meet a term for the first time.
  NAV: 'Net Asset Value. What one unit of a mutual fund is worth. It is worked out once a day after the market closes, so the newest NAV is always at least a day old.',
  'net asset value': 'What one unit of a mutual fund is worth. It is worked out once a day after the market closes, so the newest one is always at least a day old.',
  'Direct plan': 'The version of a fund you buy straight from the fund house. It pays no commission to a distributor, so it compounds faster than the Regular plan of the very same portfolio.',
  'Regular plan': 'The version of a fund sold through a distributor, who is paid a commission out of the fund every year. Same portfolio as the Direct plan, lower return.',
  IDCW: 'Income Distribution cum Capital Withdrawal. The option where the fund pays money out to you and its NAV drops by the amount paid, so its published return is measured after those payouts and cannot be compared with a Growth fund.',
  'rolling window': 'Every three-year stretch in the record, not just the last one. Asking "how often was it ahead" across all of them is far harder to flatter than quoting one lucky period.',
  'independent windows': 'How many genuinely separate stretches the history holds. Overlapping three-year windows share most of their months, so sixty of them can hold only two independent readings, which is why nothing here claims statistical significance.',
  'not comparable': 'The fund was measured perfectly well and there is nothing like-for-like to grade it against. Different from "not measured", which means this app could not see enough to measure it at all.',
  survivorship: 'Funds that shut down or merged drop out of the published tables, taking their records with them. What is left reads better than the choice you actually faced, so the app counts how many went.',
  quartile: 'Which quarter of its category the fund sat in. Upper quartile means it was in the best 25% over the period measured, which says nothing about the next period.',
  'expense ratio': 'The annual charge a fund takes out of your money, already deducted before the NAV you see. It is charged whether the fund does well or badly.',
  AMFI: 'The Association of Mutual Funds in India. It publishes the NAV of every fund daily, which is where all the fund figures here come from.',
  SEBI: 'The market regulator. It defines the fund categories used here, so a "mid cap fund" has to mean roughly the same thing at every fund house.',
  ELSS: 'Equity Linked Savings Scheme. An equity fund with a tax deduction attached and a three-year lock-in on every instalment.',
};

/** Longest-first, so "Information Coefficient" wins over a bare "IC" inside it. */
const SORTED_TERMS = Object.keys(TERMS).sort((a, b) => b.length - a.length);

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
}

/**
 * Wraps the FIRST occurrence of each known term inside `root` with a tooltip.
 *
 * Walks text nodes only, so it can never corrupt markup or double-wrap an already-glossed
 * term. Skips inputs, existing glosses, and anything marked `data-no-gloss`.
 *
 * @param {HTMLElement} root  container to scan; each call tracks its own "first use"
 */
export function glossify(root) {
  if (!root) return;

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      if (p.closest('.gloss, [data-no-gloss], script, style, input, textarea, select, title'))
        return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  const textNodes = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) textNodes.push(n);

  const used = new Set();

  for (const node of textNodes) {
    for (const term of SORTED_TERMS) {
      if (used.has(term)) continue;
      // Word-boundary-ish match. Terms containing punctuation (P/E, D/E) cannot use \b on
      // the trailing side, so assert "not immediately followed by a word character".
      const re = new RegExp(`(^|[^\\w-])(${escapeRegex(term)})(?![\\w-])`, 'i');
      const m = node.nodeValue.match(re);
      if (!m) continue;

      const at = m.index + m[1].length;
      const after = node.splitText(at);
      after.splitText(m[2].length);

      const span = document.createElement('span');
      span.className = 'gloss';
      span.title = TERMS[term];
      span.textContent = after.nodeValue;
      after.parentNode.replaceChild(span, after);

      used.add(term);
      break; // this text node is now split; move on rather than re-walking it
    }
  }
}

/**
 * The "What this means" box every section must open with (SPEC section 21 rule 1).
 * Returns a node — see SPEC section 27.3 on why nothing here builds HTML strings.
 */
export function whatThisMeans(text) {
  const div = document.createElement('div');
  div.className = 'info-box';
  const b = document.createElement('b');
  b.textContent = 'What this means: ';
  div.append(b, document.createTextNode(text));
  return div;
}
