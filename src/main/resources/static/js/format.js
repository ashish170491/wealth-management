/**
 * Formatting — the ONLY place in the UI that turns numbers into text.
 *
 * SPEC section 21 compliance is a property of this file. Rules 4 (rupees with Indian
 * comma-grouping), 6 (percentages, one decimal, explicit sign) and 7 (never render an
 * unmeasured value as a number) are implemented once here so they are reviewable by
 * reading 100 lines instead of auditing every screen.
 *
 * Do not inline `toFixed(2)` or a bare `₹` anywhere else.
 */

/** What an unmeasured value reads as. SPEC section 21 rule 7 — never 0, never blank. */
export const NOT_MEASURED = 'not measured';

const inrWhole = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
const inrPaise = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** True for anything we must not print as a number. */
export function missing(v) {
  return v === null || v === undefined || (typeof v === 'number' && !Number.isFinite(v));
}

/**
 * Rupees with Indian comma-grouping: 125000 -> "₹1,25,000".
 * Large values are abbreviated in crore/lakh, which is how the figure would actually be
 * spoken here — ₹1,25,00,000 is far harder to read at a glance than "₹1.25 Cr".
 */
export function inr(v, { paise = false, abbreviate = true } = {}) {
  if (missing(v)) return NOT_MEASURED;
  const n = Number(v);
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);

  if (abbreviate && abs >= 1e7) return `${sign}₹${(abs / 1e7).toFixed(2)} Cr`;
  if (abbreviate && abs >= 1e5) return `${sign}₹${(abs / 1e5).toFixed(2)} L`;
  return `${sign}₹${(paise ? inrPaise : inrWhole).format(abs)}`;
}

/**
 * A figure already in crore — market cap, as the exchange and every broker quote it.
 * 12345 -> "₹12,345 Cr"; above a lakh crore it reads "₹1.23 L Cr", which is how the number is spoken.
 */
export function crore(v) {
  if (missing(v)) return NOT_MEASURED;
  const n = Number(v);
  if (Math.abs(n) >= 1e5) return `₹${(n / 1e5).toFixed(2)} L Cr`;
  return `₹${inrWhole.format(Math.round(n))} Cr`;
}

/** Exact rupees, never abbreviated — for tables where figures are compared column-wise. */
export function inrExact(v, paise = false) {
  return inr(v, { paise, abbreviate: false });
}

/**
 * Percentage, one decimal, explicit sign: "+3.5%", "-12.0%".
 * Pass signed:false for magnitudes that are not gains/losses (hit rate, allocation).
 */
export function pct(v, { signed = true, digits = 1 } = {}) {
  if (missing(v)) return NOT_MEASURED;
  const n = Number(v);
  const sign = signed && n > 0 ? '+' : '';
  return `${sign}${n.toFixed(digits)}%`;
}

/** Percentage points — for drift, where "pp" and "%" mean different things. */
export function pp(v, digits = 1) {
  if (missing(v)) return NOT_MEASURED;
  const n = Number(v);
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)} pp`;
}

/** Plain number with thousands separators. */
export function num(v, digits = 0) {
  if (missing(v)) return NOT_MEASURED;
  return Number(v).toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/**
 * Correlation-style figures (Information Coefficient) need 2 decimals and a sign — at one
 * decimal, +0.10 (the conventional "useful signal" threshold) and +0.14 look identical.
 */
export function corr(v) {
  if (missing(v)) return NOT_MEASURED;
  const n = Number(v);
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}`;
}

/** 'pos' / 'neg' / '' — for colour classes. Never returns a class for a missing value. */
export function sign(v) {
  if (missing(v)) return '';
  return Number(v) > 0 ? 'pos' : Number(v) < 0 ? 'neg' : '';
}

// ------------------------------------------------------------------- symbols

/**
 * Canonical key for joins. Symbols arrive as `NSE:RELIANCE` in `symbol` but `RELIANCE` in
 * `tradingSymbol`, and different tables key differently — a mismatched client-side join
 * silently DROPS rows rather than erroring, so always join on this.
 */
export function symbolKey(s) {
  return (s || '').trim().toUpperCase();
}

/** `NSE:RELIANCE` -> `RELIANCE`, for display only. Never use as a join key. */
export function displaySymbol(s) {
  return (s || '').replace(/^(NSE|BSE|NFO):/i, '');
}

/** Link to the drill-down. Query param, not path — symbols contain a colon. */
export function stockHref(s) {
  return `stock.html?symbol=${encodeURIComponent(s)}`;
}

// --------------------------------------------------------------------- dates

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-08-24" -> "24 Aug 2026". Parsed manually to dodge timezone shifting. */
export function shortDate(iso) {
  if (!iso) return NOT_MEASURED;
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return String(iso);
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}`;
}

/** "2026-08-24T15:31:02" -> "24 Aug, 3:31 PM". */
export function dateTimeIst(iso) {
  if (!iso) return NOT_MEASURED;
  const m = String(iso).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  if (!m) return shortDate(iso);
  let h = Number(m[4]);
  const ampm = h >= 12 ? 'PM' : 'AM';
  h = h % 12 || 12;
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]}, ${h}:${m[5]} ${ampm}`;
}

/**
 * Parses a freshness stamp, and says whether it carried a time of day.
 *
 * The two shapes the health payload mixes are parsed by different rules in JavaScript, which
 * is the whole reason this helper exists. `2026-09-17T15:15:00` has no zone, so it is read as
 * LOCAL time - correct here, because the server writes IST and the reader is in IST. But a
 * bare `2026-09-17` is read as UTC midnight by specification, which is 05:30 IST: five and a
 * half hours of age this app never actually accrued.
 *
 * @returns {{date: Date, dayOnly: boolean}|null} null when unparseable
 */
function parseStamp(iso) {
  if (!iso) return null;
  const raw = String(iso).trim();
  const dayOnly = /^\d{4}-\d{2}-\d{2}$/.test(raw);
  if (dayOnly) {
    const [y, m, d] = raw.split('-').map(Number);
    return { date: new Date(y, m - 1, d), dayOnly: true };   // local midnight, not UTC
  }
  const date = new Date(raw.replace(' ', 'T'));
  return Number.isNaN(date.getTime()) ? null : { date, dayOnly: false };
}

/** Whole days between an ISO date/datetime and now. Null when unparseable. */
export function daysAgo(iso) {
  const parsed = parseStamp(iso);
  if (!parsed) return null;
  if (parsed.dayOnly) return calendarDaysAgo(parsed.date);
  return Math.floor((Date.now() - parsed.date.getTime()) / 86400000);
}

/** Whole calendar days between a local midnight and today's local midnight. */
function calendarDaysAgo(then) {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((today.getTime() - then.getTime()) / 86400000);
}

/**
 * "just now" / "3 hours ago" / "today" / "yesterday" — for the freshness strip.
 *
 * Five of the twelve freshness keys (screening scores, holdings history, pick outcomes, core
 * tiers, watchlist history) are stored as a DATE, because the table behind them is keyed by
 * date and no run time was ever recorded. An hours-ago phrasing for those is inventing a
 * precision nobody has: read as UTC midnight, today's 14:00 screening reported itself as
 * "9 hours ago", which is the freshness strip - on every page - telling the investor their
 * data is far older than it is. Answer a date in days and say "today" when it is today
 * (SPEC 21 rule 7: never render an assumption as a fact).
 */
export function relative(iso) {
  if (!iso) return 'never';
  const parsed = parseStamp(iso);
  if (!parsed) return String(iso);

  if (parsed.dayOnly) {
    const days = calendarDaysAgo(parsed.date);
    if (days <= 0) return 'today';
    if (days === 1) return 'yesterday';
    return `${days} days ago`;
  }

  const mins = Math.floor((Date.now() - parsed.date.getTime()) / 60000);
  if (mins < 2) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} hour${hrs === 1 ? '' : 's'} ago`;
  const days = Math.floor(hrs / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

// ---------------------------------------------------------------- enum labels

/**
 * Human labels for enums. SPEC section 21 rule 3: "Thesis Broken", not `BROKEN`.
 * Enum strings stay as-is in the API and DB — this is display only.
 */
const LABELS = {
  STRONG_BUY: 'Strong Buy', BUY: 'Buy', ACCUMULATE: 'Accumulate', HOLD: 'Hold',
  WATCH: 'Watch', AVOID: 'Avoid', SELL: 'Sell', STRONG_SELL: 'Strong Sell', REDUCE: 'Reduce',

  INTACT: 'Thesis Intact', DECAYING: 'Thesis Decaying', BROKEN: 'Thesis Broken',
  STALE: 'No Recent Score', NO_DATA: 'Not Screened', UNDER_REVIEW: 'Under Review',

  BULLISH: 'Rising', BEARISH: 'Falling', SIDEWAYS: 'Flat', MIXED: 'Mixed', NEUTRAL: 'Neutral',

  IN_TOLERANCE: 'On Target', OVER_TOLERANCE: 'Over Tolerance', NO_TARGET: 'No Target Set',

  LOW: 'Well Spread', MODERATE: 'Moderately Concentrated',
  CONCENTRATED: 'Concentrated', DANGEROUS: 'Dangerously Concentrated',

  LONG_TERM: 'Long-Term', SHORT_TERM: 'Short-Term',
  LTCG_ELIGIBLE: 'Long-Term Eligible', APPROACHING_LTCG: 'Nearly Long-Term',
  LOSS_HARVEST: 'Loss-Harvest Candidate',

  STRONG_MULTIBAGGER: 'Strong Candidate', POTENTIAL_MULTIBAGGER: 'Potential Candidate',
  WATCHLIST: 'Watchlist', MONITOR: 'Monitor',

  HIGH_QUALITY: 'High Quality', DECENT: 'Decent', AVERAGE: 'Average',
  WEAK: 'Weak', HIGH_RISK: 'High Risk', NA_FINANCIAL: 'Not Applicable (Financial)',

  DEEPLY_UNDERVALUED: 'Deeply Undervalued', UNDERVALUED: 'Undervalued',
  FAIRLY_VALUED: 'Fairly Valued', EXPENSIVE: 'Expensive',
  EXTREMELY_EXPENSIVE: 'Extremely Expensive', NOT_APPLICABLE: 'Not Applicable',
  INSUFFICIENT_DATA: 'Not Enough Data',

  URGENT: 'Urgent', WARNING: 'Worth a Look', INFO: 'For Information', OPPORTUNITY: 'Opportunity',
  CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium',

  EXIT_SIGNAL: 'Exit Signal', THESIS_DECAY: 'Thesis Drift',
  ALLOCATION_DRIFT: 'Allocation Drift', CONCENTRATION_RISK: 'Concentration Risk',

  NEAR_RESISTANCE: 'Near Resistance', RSI_OVERBOUGHT: 'Looks Overbought',
  BROKE_SUPPORT: 'Broke Support', DEEP_LOSS_ACCELERATING: 'Deepening Loss',
  MOMENTUM_REVERSAL: 'Momentum Turned',

  MULTIBAGGER: 'Multibagger Screener', QUANT_DISCOVERY: 'Quantitative Discovery',
  SECTOR_REVERSAL: 'Sector Reversal',

  SMALL_CAP: 'Small Cap', MID_CAP: 'Mid Cap', LARGE_CAP: 'Large Cap',
  SIP: 'Monthly SIP', PRICE_LADDER: 'Price Ladder', SIGNAL_GATED: 'Signal-Gated',
  EXPANDING: 'Expanding', CONTRACTING: 'Contracting',

  // Watchlist "still a good time to buy?" verdicts (SPEC 37.3)
  BUY_NOW: 'Buy Now', WAIT_FOR_PULLBACK: 'Wait for a Dip', HOLD_OFF: 'Hold Off',
  NOT_MEASURED: 'Not Measured',
  SEED: 'From Config', MANUAL: 'Added by You',
  LIQUID: 'Liquid', THIN: 'Thinly Traded', UNKNOWN: 'Unknown',

  // Sector buckets (SectorMapping.java). Title Case would render "It" and "Fmcg".
  IT: 'IT', FMCG: 'FMCG', PHARMA: 'Pharma', FINANCIALS: 'Financials', BANKING: 'Banking',
  CONSUMER_DURABLES: 'Consumer Durables', CONSUMER_SERVICES: 'Consumer Services',
  CAPITAL_GOODS: 'Capital Goods', DEFENSE: 'Defence', REALTY: 'Real Estate',
  INFRASTRUCTURE: 'Infrastructure', ENERGY: 'Energy', METALS: 'Metals', AUTO: 'Auto',
  CHEMICALS: 'Chemicals', TELECOM: 'Telecom', MEDIA: 'Media', TEXTILES: 'Textiles',
  LOGISTICS: 'Logistics', RETAIL: 'Retail', SERVICES: 'Services', DIVERSIFIED: 'Diversified',
  MATERIALS: 'Materials', AGRI: 'Agriculture',

  // Earnings-growth verdicts (NseDataService.analyzeEarningsGrowth)
  STRONG_GROWTH: 'Strong Growth', MODERATE_GROWTH: 'Moderate Growth',
  STAGNANT: 'Stagnant', DECLINING: 'Declining',

  // Macro exposure verdicts (SPEC 48). MIXED and NOT_MEASURED are already above and mean the
  // same thing here, which is why they are not repeated.
  TAILWIND: 'Tailwind', HEADWIND: 'Headwind', NOT_EXPOSED: 'Not Affected',
  KEYWORD: 'Keyword rules',

  // Every MacroFactor (MacroFactor.java), character for character with its label() there.
  //
  // These are not decoration. Without them the Title-Case fallback renders USDINR as "Usdinr"
  // and REGULATORY_PHARMA_USFDA as "Regulatory Pharma Usfda" — an enum name with the shouting
  // taken out, which is not the same thing as a human label (SPEC 21 rule 3).
  CRUDE_OIL: 'Crude oil',
  METALS_PRICES: 'Metal and commodity prices',
  COAL_POWER_PRICES: 'Coal and power prices',
  GOLD: 'Gold',
  FOOD_INFLATION: 'Food inflation',
  INTEREST_RATES: 'RBI interest rates',
  USDINR: 'Rupee vs dollar',
  US_RATES: 'US Fed rates',
  US_TARIFFS: 'US tariffs',
  TRADE_BARRIERS_CHINA: 'Chinese import pressure',
  GLOBAL_DEMAND_SLOWDOWN: 'Global demand slowdown',
  GOVT_CAPEX: 'Government capital spending',
  DEFENCE_SPEND: 'Defence spending',
  GEOPOLITICAL_CONFLICT_REGIONAL: 'Conflict near India',
  GEOPOLITICAL_CONFLICT_GLOBAL: 'Conflict elsewhere in the world',
  MONSOON_DEFICIT: 'Monsoon shortfall',
  REGULATORY_TELECOM: 'Telecom regulation',
  REGULATORY_CAPITAL_MARKETS: 'Market regulation',
  REGULATORY_NBFC: 'Lending regulation',
  REGULATORY_SIN_GOODS: 'Tobacco and alcohol taxes',
  REGULATORY_PHARMA_USFDA: 'US drug regulation',
};

export function humanLabel(v) {
  if (v === null || v === undefined || v === '') return NOT_MEASURED;
  const key = String(v).toUpperCase();
  if (LABELS[key]) return LABELS[key];
  // Fall back to Title Case so an unmapped enum still reads as words, not SHOUTING.
  return key.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

/** Maps a recommendation/verdict to a .badge class. */
export function badgeType(v) {
  const key = String(v || '').toUpperCase();
  if (['STRONG_BUY', 'BUY', 'ACCUMULATE', 'INTACT', 'HIGH_QUALITY', 'STRONG_MULTIBAGGER',
       'LOW', 'IN_TOLERANCE', 'DEEPLY_UNDERVALUED', 'UNDERVALUED', 'EXPANDING', 'BUY_NOW', 'LIQUID',
       'STRONG_GROWTH', 'TAILWIND'].includes(key)) return 'success';
  if (['SELL', 'STRONG_SELL', 'AVOID', 'BROKEN', 'HIGH_RISK', 'DANGEROUS', 'URGENT',
       'CRITICAL', 'EXTREMELY_EXPENSIVE', 'CONTRACTING', 'THIN', 'DECLINING', 'HEADWIND'].includes(key)) return 'danger';
  if (['REDUCE', 'DECAYING', 'WEAK', 'CONCENTRATED', 'OVER_TOLERANCE', 'WARNING',
       'EXPENSIVE', 'MONITOR', 'WAIT_FOR_PULLBACK'].includes(key)) return 'warning';
  if (['NO_DATA', 'STALE', 'INSUFFICIENT_DATA', 'NOT_APPLICABLE', 'NA_FINANCIAL', 'NOT_MEASURED', 'UNKNOWN'].includes(key)) return 'unmeasured';
  if (['HOLD', 'WATCH', 'NEUTRAL', 'MIXED', 'SIDEWAYS', 'AVERAGE', 'MODERATE', 'HOLD_OFF', 'SEED', 'MANUAL',
       'STAGNANT', 'NOT_EXPOSED'].includes(key)) return 'neutral';
  return 'info';
}

/** 'high' | 'mid' | 'low' for a 0-100 score bar. */
export function scoreBand(score) {
  if (missing(score)) return 'low';
  return score >= 65 ? 'high' : score >= 40 ? 'mid' : 'low';
}
