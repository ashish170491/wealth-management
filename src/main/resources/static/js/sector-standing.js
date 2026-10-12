/**
 * "How does this business compare with its own sector?" — the sector-relative lens (SPEC 54).
 *
 * The reading is computed server-side by SectorStanding.java and arrives on each row as
 * `sectorStanding` plus four companion fields. Nothing is decided here: this file only chooses a
 * wording and a colour, the same split as compounding.js.
 *
 * Three things this must never do.
 *  - Present itself as a reason to own a stock. Being strong for its sector is not the same as
 *    compounding; the compounding cell beside it is the market-wide question, and this one only
 *    explains where the business sits among its own kind (Gotcha 105).
 *  - Draw NOT_RANKED like "not measured". NOT_RANKED means the app looked and no peer group could
 *    rank the stock (too few peers, a mixed group, too few checks); null means it never looked.
 *    Two facts, two renderings (Gotcha 121).
 *  - Use red. "Below its sector" is amber, because red reads as "sell" and a quartile is not that.
 */
import { el, badge, unmeasured } from './ui.js';

/** Verdict -> how it reads and how it looks. Plain words, no jargon (SPEC 21). */
export const SECTOR_STANDING = {
  ABOVE_PEERS: { type: 'success', label: 'Above', long: 'Stronger than most of its sector' },
  IN_LINE: { type: 'info', label: 'Middle', long: 'In the middle of its sector' },
  BELOW_PEERS: { type: 'warning', label: 'Below', long: 'Weaker than most of its sector' },
  NOT_RANKED: { type: 'neutral', label: 'Not ranked', long: 'Could not be compared with its sector' },
};

/** Sort order: above, middle, below, then not-ranked, with never-looked always last. */
export function sectorStandingRank(row) {
  const v = row && row.sectorStanding;
  if (v === 'ABOVE_PEERS') return 0;
  if (v === 'IN_LINE') return 1;
  if (v === 'BELOW_PEERS') return 2;
  if (v === 'NOT_RANKED') return 3;
  return 4;
}

const CAVEAT = ' Compared only with the other stocks this app screens in the same sector — '
  + 'strong for its kind is not the same as compounding, and this does not change any score.';

/** One table cell. The reason goes in the tooltip; the full breakdown is on the stock page. */
export function sectorStandingCell(row) {
  const v = row && row.sectorStanding;
  if (!v) {
    return unmeasured('Not in the latest screening run, so there is no sector comparison');
  }
  const spec = SECTOR_STANDING[v];
  if (!spec) return unmeasured('Unrecognised reading');

  const above = row.sectorStandingAbove;
  const ranked = row.sectorStandingRanked;
  const label = v === 'NOT_RANKED' || ranked == null ? spec.label : `${spec.label} ${above}/${ranked}`;
  const node = badge(v, { type: spec.type, label });
  node.title = (row.sectorStandingReason || spec.long) + CAVEAT;
  return node;
}

/** A sortable column for any table whose rows carry the five fields. */
export function sectorStandingCol() {
  return {
    key: 'sectorStanding',
    label: 'Vs sector',
    title: 'How this business compares with others in its own sector on return on capital, '
      + 'cash, debt and steadiness. It explains a reading; it does not change a score.',
    value: sectorStandingRank,
    render: sectorStandingCell,
  };
}

const QUARTILE = {
  TOP_QUARTILE: { label: 'Top quarter', cls: 'pos' },
  UPPER_MIDDLE: { label: 'Upper middle', cls: '' },
  LOWER_MIDDLE: { label: 'Lower middle', cls: '' },
  BOTTOM_QUARTILE: { label: 'Bottom quarter', cls: '' },
};

const REFUSAL = {
  NOT_APPLICABLE: 'Does not apply',
  NOT_MEASURED: 'Not on record',
  TOO_FEW_PEERS: 'Too few peers',
  MIXED_PEER_GROUP: 'Mixed group',
  NOT_CLASSIFIED: 'No sector',
};

function fmt(key, v) {
  if (v === null || v === undefined) return '—';
  if (key === 'ROCE' || key === 'ROA' || key === 'ROE') return `${v.toFixed(1)}%`;
  if (key === 'STEADINESS') return v.toFixed(0);
  return v.toFixed(2);
}

/** The bucket as a reader would write it: "capital goods", but "IT" and "FMCG". */
function sectorWords(s) {
  if (!s) return 'its sector';
  if (s === 'OIL_GAS') return 'oil and gas';
  return s.length <= 4 ? s : s.replace(/_/g, ' ').toLowerCase();
}

/**
 * The full breakdown for the stock page. Every figure is listed, including the ones that could not
 * be ranked and why — a breakdown that quietly dropped them would let a business look thoroughly
 * compared when it was barely compared at all (Gotcha 44).
 */
export function sectorStandingPanel(data) {
  if (!data) return null;
  const v = data.sectorStanding;
  const spec = SECTOR_STANDING[v];

  const head = el('div.row.wrap', { style: 'gap:12px;align-items:center;margin-bottom:10px' },
    spec ? badge(v, { type: spec.type, label: spec.label }) : unmeasured('No reading'),
    el('span', { style: 'font-weight:600;font-size:14px' },
      spec ? `${spec.long} (${sectorWords(data.sectorStandingSector)})` : 'No sector comparison is available.'));

  const metrics = Array.isArray(data.sectorStandingMetrics) ? data.sectorStandingMetrics : [];
  const list = el('div', {});
  list.append(el('div.row.muted', { style: 'gap:10px;font-size:12px;margin-bottom:6px' },
    el('span', { style: 'flex:0 0 240px' }, 'Check'),
    el('span', { style: 'flex:0 0 80px' }, 'This stock'),
    el('span', { style: 'flex:0 0 100px' }, 'Sector median'),
    el('span', { style: 'flex:0 0 55px' }, 'Peers'),
    el('span', { style: 'flex:0 0 120px' }, 'Where it sits')));
  for (const m of metrics) {
    const ranked = m.status === 'RANKED';
    const q = ranked ? QUARTILE[m.quartile] : null;
    list.append(el('div.row', { style: 'gap:10px;align-items:baseline;margin-bottom:6px', title: m.detail || '' },
      el('span', { style: 'flex:0 0 240px;font-size:13.5px' + (ranked ? '' : ';opacity:.65') },
        m.label + (m.higherIsBetter === false ? ' (lower is better)' : '')),
      // A figure that does not apply to this kind of business is not printed: a lender's ROCE or a
      // manufacturer's ROA beside "Does not apply" reads as a measurement being ignored.
      el('span', { style: 'flex:0 0 80px;font-size:13.5px' },
        m.status === 'NOT_APPLICABLE' ? '—' : fmt(m.key, m.value)),
      el('span.muted', { style: 'flex:0 0 100px;font-size:13px' }, ranked ? fmt(m.key, m.sectorMedian) : '—'),
      el('span.muted', { style: 'flex:0 0 55px;font-size:13px' }, m.peers == null ? '—' : String(m.peers)),
      ranked
        ? el('span' + (q && q.cls ? '.' + q.cls : ''), { style: 'flex:0 0 120px;font-size:13px;font-weight:600' },
          q ? q.label : m.quartile)
        : el('span.muted', { style: 'flex:0 0 120px;font-size:13px' }, REFUSAL[m.status] || m.status)));
  }

  const foot = el('div', {
    style: 'margin-top:12px;padding-top:10px;border-top:1px solid var(--rule);font-size:13px',
  });
  if (data.sectorStandingReason) {
    foot.append(el('div', { style: 'margin-bottom:6px' }, data.sectorStandingReason));
  }
  const div = Array.isArray(data.sectorStandingDivergences) ? data.sectorStandingDivergences : [];
  if (div.length) {
    const box = el('div.info-box', { style: 'margin:8px 0' },
      el('b', {}, 'Where this and the compounding check disagree'));
    for (const d of div) box.append(el('div', { style: 'margin-top:4px' }, d));
    foot.append(box);
  }
  foot.append(el('div.muted', { style: 'margin-top:5px' },
    'Ranks are by quarter, among the other stocks this app screens in the same sector, and only '
    + 'where at least ten of them report the figure. Lenders are compared only with lenders. '
    + 'Gross margin and P/E are left out: the first reads 100% for businesses with no cost of '
    + 'goods, the second is not stored on the screening row.'));
  foot.append(el('div.muted', { style: 'margin-top:5px' },
    'Being strong for its sector is not the same as compounding — a business still has to earn '
    + 'more than its cost of capital. This does not change any score.'));

  return el('div', {}, head, list, foot);
}
