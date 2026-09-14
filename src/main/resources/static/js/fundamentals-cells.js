/**
 * The business-number cells the screener draws beside the composite (SPEC 12.5, 2026-09-09).
 *
 * Every figure here already lives on the screening row — ROCE, debt-to-equity, the DCF verdict,
 * forensic flags, promoter holding, growth — and for months was sent to the page and never drawn,
 * while the table's visual centre was seven score bars, four of them price behaviour with near-zero
 * IC (B-023). These cells put the business first. Nothing is decided here: each cell reads a
 * server-side figure or verdict and chooses a wording and a colour, and none of them blends anything
 * into a second score (Gotcha 85 — the compounding gate is the summary; these are the evidence).
 *
 * Null discipline (SPEC 21 rule 7, Gotcha 21/44): an absent figure is the striped "not measured"
 * marker with a reason, never 0, never a dash that looks like a value. The one deliberate
 * exception is a metric that does not apply — ROCE and D/E for a lender — which reads "n/a" so a
 * bank is not mistaken for a company whose accounts could not be read.
 *
 * One renderer, several surfaces: the discovery tables and the stock page may reuse these so a
 * figure cannot read one way on one screen and another way on the next (SPEC 41.5).
 */
import { el, badge, unmeasured } from './ui.js';
import { pct, num, crore, humanLabel, missing, sign } from './format.js';

// ------------------------------------------------------------------ helpers

/** A lender: ROCE and D/E on an equity-only base mislead, so the engine suppresses them. */
export function isLender(row) {
  return !!row && String(row.capexVerdict || '').toUpperCase() === 'NA_FINANCIAL';
}

function sub(text, cls) {
  return el('span.cell-sub' + (cls ? '.' + cls : ''), {}, text);
}

function twoLine(main, subText, { cls, title } = {}) {
  const node = el('span', title ? { title } : {});
  node.append(el('span.cell-main' + (cls ? '.' + cls : ''), {}, main));
  if (subText) node.append(sub(subText));
  return node;
}

function notApplicable(reason) {
  return el('span.faint', { title: reason }, 'n/a');
}

// ---------------------------------------------------------- financial quality

const FIN_RANK = { HIGH_QUALITY: 0, DECENT: 1, AVERAGE: 2, WEAK: 3, HIGH_RISK: 4 };

export function finQualityRank(row) {
  const v = String((row && row.financialQualityVerdict) || '').toUpperCase();
  return v in FIN_RANK ? FIN_RANK[v] : 99;
}

export function finQualityCell(row) {
  const v = row && row.financialQualityVerdict;
  if (!v) return unmeasured('Financial quality could not be measured — NSE quarterly filings not available for this stock');
  const node = badge(v);
  const bits = [];
  if (!missing(row.financialQualityScore)) bits.push(`score ${row.financialQualityScore}/100`);
  if (!missing(row.interestCoverage)) bits.push(`interest cover ${num(row.interestCoverage, 1)}×`);
  if (!missing(row.cashConversionRatio)) bits.push(`cash conversion ${num(row.cashConversionRatio, 2)}×`);
  if (!missing(row.ocfToProfitRatio)) bits.push(`cash-flow to profit ${num(row.ocfToProfitRatio, 2)}×`);
  node.title = 'Balance-sheet and cash-flow depth from NSE quarterly filings. '
    + (bits.length ? bits.join(' · ') + '.' : '')
    + (v === 'HIGH_RISK' ? ' A high-risk verdict caps the composite at 54.' : '');
  return node;
}

// ----------------------------------------------------------------- ROCE / ROA

/** Sort value: ROCE. Lenders have none and sort last — their ROA is a different scale. */
export function roceValue(row) {
  return row ? row.rocePercent : null;
}

export function roceCell(row) {
  if (!row) return unmeasured();
  const tip = [];
  if (!missing(row.roePercent)) tip.push(`ROE ${pct(row.roePercent, { signed: false })}`);
  if (!missing(row.roaPercent)) tip.push(`ROA ${pct(row.roaPercent, { signed: false })}`);
  if (!missing(row.cashConversionRatio)) tip.push(`cash conversion ${num(row.cashConversionRatio, 2)}×`);

  if (!missing(row.rocePercent)) {
    const v = Number(row.rocePercent);
    const cls = v >= 20 ? 'pos' : v < 10 ? 'neg' : '';
    return twoLine(pct(v, { signed: false }), null, {
      cls,
      title: 'Return on capital employed from the latest annual accounts. Above 20% is strong, under 10% is weak.'
        + (tip.length ? ' ' + tip.join(' · ') + '.' : ''),
    });
  }
  if (isLender(row) && !missing(row.roaPercent)) {
    const v = Number(row.roaPercent);
    const cls = v >= 1.5 ? 'pos' : v < 0.8 ? 'neg' : '';
    return twoLine(pct(v, { signed: false }), 'ROA (lender)', {
      cls,
      title: 'A bank or NBFC: ROCE on an equity-only base misleads, so return on assets is shown instead. '
        + 'Above 1.5% is strong, under 0.8% is weak. Sorts last on this column because it is a different scale.'
        + (tip.length ? ' ' + tip.join(' · ') + '.' : ''),
    });
  }
  if (isLender(row)) return notApplicable('A lender: ROCE does not apply, and its return on assets was not available');
  return unmeasured('Return on capital could not be read from the latest annual filing');
}

// --------------------------------------------------------------- debt/equity

export function leverageValue(row) {
  return row ? row.debtToEquity : null;
}

export function leverageCell(row) {
  if (!row) return unmeasured();
  if (!missing(row.debtToEquity)) {
    const v = Number(row.debtToEquity);
    const cls = v <= 0.3 ? 'pos' : v > 2 ? 'neg' : '';
    return twoLine(num(v, 2), null, {
      cls,
      title: 'Total borrowings divided by shareholders’ equity. Under 0.3 is conservative, over 2 is risky.'
        + (!missing(row.interestCoverage) ? ` Interest cover ${num(row.interestCoverage, 1)}×.` : ''),
    });
  }
  if (isLender(row)) return notApplicable('A lender: borrowing is the business, so debt-to-equity is not a risk measure here');
  return unmeasured('Borrowings and equity could not be read from the latest annual filing');
}

// -------------------------------------------------------------------- growth

export function growthValue(row) {
  return row ? row.yoyProfitGrowth : null;
}

export function growthCell(row) {
  if (!row) return unmeasured();
  const profit = row.yoyProfitGrowth;
  const revenue = row.yoyRevenueGrowth;
  const verdict = row.earningsGrowthVerdict;
  if (missing(profit) && missing(revenue) && !verdict) {
    return unmeasured('Growth not measured — NSE quarterly results were not available for this stock on the last screening');
  }
  const main = missing(profit) ? 'profit n/m' : pct(profit);
  const subText = verdict ? humanLabel(verdict) : (missing(revenue) ? null : `revenue ${pct(revenue)}`);
  const tip = 'Latest quarter against the same quarter a year earlier (YoY).'
    + (missing(profit) ? ' Profit growth could not be measured.' : ` Profit ${pct(profit)}.`)
    + (missing(revenue) ? '' : ` Revenue ${pct(revenue)}.`)
    + (verdict ? ` Verdict: ${humanLabel(verdict)}.` : '');
  return twoLine(main, subText, { cls: missing(profit) ? '' : sign(profit), title: tip });
}

// ------------------------------------------------------------------ promoter

export function promoterValue(row) {
  return row ? row.promoterHoldingPct : null;
}

export function promoterCell(row) {
  if (!row) return unmeasured();
  const holding = row.promoterHoldingPct;
  const change = row.promoterHoldingChangePct;
  const pledge = row.promoterPledgePercent;
  if (missing(holding) && missing(change) && missing(pledge)) {
    return unmeasured('Shareholding pattern not available for this stock on the last screening');
  }
  const parts = [];
  if (!missing(change)) parts.push(`${Number(change) > 0 ? '▲' : Number(change) < 0 ? '▼' : '▶'} ${pct(change)} pts`);
  if (!missing(pledge) && Number(pledge) > 0) parts.push(`pledge ${pct(pledge, { signed: false, digits: 0 })}`);

  const node = el('span', {});
  node.append(missing(holding)
    ? unmeasured('Promoter holding not available on the last screening')
    : el('span.cell-main', {}, pct(holding, { signed: false })));
  if (parts.length) {
    const s = sub(parts.join(' · '));
    if (!missing(pledge) && Number(pledge) > 20) s.classList.add('neg');
    else if (!missing(change) && Number(change) > 0) s.classList.add('pos');
    node.append(s);
  }
  const tip = [];
  tip.push('Share of the company the promoters (founding owners) hold, from the latest quarterly filing.');
  if (!missing(change)) tip.push(`Change over the quarters on file: ${pct(change)} percentage points.`);
  if (!missing(pledge)) tip.push(`Pledged as loan collateral: ${pct(pledge, { signed: false })}${Number(pledge) > 20 ? ' — a risk flag.' : '.'}`);
  if (!missing(row.fiiHoldingPct)) tip.push(`FII ${pct(row.fiiHoldingPct, { signed: false })}.`);
  if (!missing(row.diiHoldingPct)) tip.push(`DII ${pct(row.diiHoldingPct, { signed: false })}.`);
  node.title = tip.join(' ');
  return node;
}

// ----------------------------------------------------------------- valuation

const DCF_RANK = {
  DEEPLY_UNDERVALUED: 0, UNDERVALUED: 1, FAIRLY_VALUED: 2, EXPENSIVE: 3, EXTREMELY_EXPENSIVE: 4,
};

/**
 * Short pill labels. "Extremely Expensive" set the column's floor at 102 px on its own; the full
 * verdict is the first words of the tooltip, so nothing is lost (SPEC 27.10 rule 5).
 */
const DCF_LABEL = {
  DEEPLY_UNDERVALUED: 'Very cheap', UNDERVALUED: 'Cheap', FAIRLY_VALUED: 'Fair',
  EXPENSIVE: 'Dear', EXTREMELY_EXPENSIVE: 'Very dear',
};

function dcfUsable(v) {
  const k = String(v || '').toUpperCase();
  return k in DCF_RANK;
}

/** Sorts cheapest first. A PE-only reading ranks by deviation around the fair band; unmeasured last. */
export function valuationRank(row) {
  if (!row) return 99;
  if (dcfUsable(row.dcfVerdict)) return DCF_RANK[String(row.dcfVerdict).toUpperCase()];
  if (!missing(row.peDeviation)) {
    const d = Number(row.peDeviation);
    return d < -20 ? 1 : d > 20 ? 3 : 2;
  }
  return 99;
}

export function valuationCell(row) {
  if (!row) return unmeasured();
  const tip = [];
  if (!missing(row.peDeviation)) {
    const d = Number(row.peDeviation);
    tip.push(`P/E is ${pct(Math.abs(d), { signed: false, digits: 0 })} ${d < 0 ? 'below' : 'above'} its sector.`);
  }
  if (dcfUsable(row.dcfVerdict)) {
    if (!missing(row.dcfImpliedGrowthPercent)) {
      tip.push(`Today’s price assumes ${pct(row.dcfImpliedGrowthPercent, { signed: false, digits: 0 })} a year growth for 10 years`
        + (missing(row.dcfHistoricalGrowthPercent) ? '.' : `, against ${pct(row.dcfHistoricalGrowthPercent, { signed: false, digits: 0 })} actually delivered.`));
    }
    if (!missing(row.dcfExpectationGapPercent)) tip.push(`Expectation gap ${pct(row.dcfExpectationGapPercent, { digits: 0 })} pts.`);
    tip.push('Reverse-DCF is a sanity check, not a price target, and it under-values long-duration compounders.');
    const key = String(row.dcfVerdict).toUpperCase();
    const node = badge(row.dcfVerdict, { label: DCF_LABEL[key] || humanLabel(row.dcfVerdict) });
    node.title = `${humanLabel(row.dcfVerdict)} on reverse-DCF. ` + tip.join(' ');
    return node;
  }
  if (!missing(row.peDeviation)) {
    const d = Number(row.peDeviation);
    const label = d < -20 ? 'Cheap (P/E)' : d > 20 ? 'Dear (P/E)' : 'Fair (P/E)';
    const type = d < -20 ? 'success' : d > 20 ? 'warning' : 'neutral';
    tip.push(String(row.dcfVerdict || '').toUpperCase() === 'NOT_APPLICABLE'
      ? 'Reverse-DCF does not apply (loss-making), so this is the P/E reading alone.'
      : 'Reverse-DCF could not be computed, so this is the P/E reading alone.');
    const node = badge('PE_ONLY', { type, label });
    node.title = tip.join(' ');
    return node;
  }
  return unmeasured('Neither a P/E against its sector nor a reverse-DCF could be computed for this stock');
}

// ----------------------------------------------------------------- red flags

const SEV_ORDER = { HIGH: 0, MEDIUM: 1, UNKNOWN: 1, INFO: 2 };

/**
 * Mirror of ForensicSeverity.java: CODE:severity tokens, split on , ; |. A token with no
 * parseable severity is UNKNOWN and treated as a caution — unknown is not "informational".
 */
function parseFlags(flags) {
  if (!flags || !String(flags).trim()) return [];
  const out = [];
  for (const raw of String(flags).split(/[,;|]/)) {
    const t = raw.trim();
    if (!t) continue;
    const upper = t.toUpperCase();
    if (upper === 'NONE' || upper === 'CLEAN') continue;
    const colon = t.lastIndexOf(':');
    const code = colon > 0 ? t.slice(0, colon).trim() : t;
    const suffix = colon >= 0 ? t.slice(colon + 1).trim().toUpperCase() : '';
    let sev = 'UNKNOWN';
    if (suffix === 'HIGH' || suffix === 'MEDIUM' || suffix === 'INFO') sev = suffix;
    else if (upper.startsWith('INFO')) sev = 'INFO';
    out.push({ code: shortCode(code), sev });
  }
  return out;
}

/** The persisted CODE, made fit for a pill: "CASH_CONVERSION" -> "cash conv." */
function shortCode(code) {
  const c = String(code || '').toUpperCase().replace(/[\s-]+/g, '_');
  const SHORT = {
    DILUTION: 'dilution', RECEIVABLES: 'receivables', CASH_CONVERSION: 'cash conv.',
    AUDITOR: 'auditor', RELATED_PARTY: 'related party', CORPORATE_ACTION: 'corp. action',
  };
  return SHORT[c] || c.replace(/_/g, ' ').toLowerCase();
}

/** Everything the row says against the stock, forensic flags plus the two balance-sheet stops. */
export function redFlags(row) {
  if (!row) return { checked: false, flags: [] };
  const checked = row.forensicFlags !== null && row.forensicFlags !== undefined;
  const flags = parseFlags(row.forensicFlags);
  if (String(row.financialQualityVerdict || '').toUpperCase() === 'HIGH_RISK') {
    flags.unshift({ code: 'high risk', sev: 'HIGH' });
  }
  if (!missing(row.promoterPledgePercent) && Number(row.promoterPledgePercent) > 20) {
    flags.push({ code: `pledge ${Math.round(Number(row.promoterPledgePercent))}%`, sev: 'MEDIUM' });
  }
  return { checked, flags };
}

/** 'HIGH' | 'MEDIUM' | 'INFO' | 'NONE' when the screen ran and found nothing | null when it never ran. */
export function worstFlag(row) {
  const { checked, flags } = redFlags(row);
  if (flags.length === 0) return checked ? 'NONE' : null;
  let worst = 'INFO';
  for (const f of flags) if (SEV_ORDER[f.sev] < SEV_ORDER[worst]) worst = f.sev === 'UNKNOWN' ? 'MEDIUM' : f.sev;
  return worst;
}

export function redFlagsRank(row) {
  const w = worstFlag(row);
  return w === 'HIGH' ? 0 : w === 'MEDIUM' ? 1 : w === 'INFO' ? 2 : w === 'NONE' ? 3 : 99;
}

export function redFlagsCell(row) {
  const { checked, flags } = redFlags(row);
  if (flags.length === 0) {
    if (!checked) {
      // Gotcha 44: "no flags" usually means "nothing was checked". The forensic screen needs three
      // or more years of annual accounts on file, which most of the universe does not yet have.
      return unmeasured('Not checked — the forensic screen needs 3+ years of annual accounts on file for this stock');
    }
    return badge('NONE', { type: 'success', label: 'None found' });
  }
  const node = el('span', { style: 'display:inline-flex;flex-wrap:wrap;gap:3px' });
  for (const f of flags) {
    const type = f.sev === 'HIGH' ? 'danger' : f.sev === 'INFO' ? 'neutral' : 'warning';
    const b = badge(f.code, { type, label: f.code });
    const what = f.code === 'high risk' ? 'Financial-quality verdict HIGH_RISK (loss-making, very high pledge, interest cover or cash conversion in the danger zone). '
      : f.code === 'cash conv.' ? 'Reported profit is not turning into operating cash. '
        : f.code === 'corp. action' ? 'A bonus or split was divided out of the share count before growth was measured. '
          : '';
    b.title = what + (f.sev === 'HIGH'
      ? 'Serious: outweighs any entry signal and caps the composite.'
      : f.sev === 'INFO' ? 'For information only; scores zero.'
        : 'A caution: worth understanding before buying, not disqualifying on its own.');
    node.append(b);
  }
  return node;
}

// ------------------------------------------------------------ market cap etc

export function marketCapCell(row) {
  if (!row || missing(row.marketCapCrores)) {
    return unmeasured('Market cap could not be computed — share count not readable from the latest filing');
  }
  return twoLine(crore(row.marketCapCrores), humanLabel(row.marketCapCategory), {
    title: 'Share price times shares outstanding, in crore, at the last screening.',
  });
}

export function sectorCell(row) {
  const s = row && row.sector;
  if (!s) return unmeasured('Sector not classified for this stock');
  // A sector name is a label, not prose: it wraps at a space but never mid-word ("Chemical / s"),
  // the same floor a ticker or a pill keeps (SPEC 27.10 rule 2).
  return el('span', {
    style: 'overflow-wrap:normal',
    title: row.industry && row.industry !== s ? `Filed by NSE as: ${row.industry}` : 'From NSE’s index classification',
  }, humanLabel(s));
}

/**
 * "you own · +12.3%" under the ticker for a holding, nothing otherwise — absence here means not
 * owned, not unmeasured. A sub-line rather than a column: it cost 72 px of table floor for a fact
 * the Owned filter already answers (SPEC 27.10).
 */
export function ownedLine(row) {
  if (!row || !row.inHoldings) return null;
  const ret = missing(row.holdingsPnlPercent) ? '' : ` · ${pct(row.holdingsPnlPercent)}`;
  return el('span.cell-sub.' + (sign(row.holdingsPnlPercent) || 'muted'), {
    title: 'In your portfolio' + (ret ? `, unrealised return ${pct(row.holdingsPnlPercent)}` : ''),
  }, 'you own' + ret);
}
