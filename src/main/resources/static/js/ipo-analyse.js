/**
 * "Analyse this listing" — the one control that fills a recent listing's business columns.
 *
 * Shared by the IPOs page and Discovery's Recent Listings table for the reason `watch-button.js`
 * is shared: the same control on two screens must be one implementation, or the two drift and
 * the second one quietly stops matching the guard, the wording or the timeout.
 *
 * What it is for. A newly listed company has no filings history, so `financialQualityVerdict`,
 * `earningsVerdict`, `promoterHoldingPct` and the composite arrive **null on every row** and stay
 * null until somebody asks for that listing specifically. Measured on the live feed (2026-09-10):
 * 1 of 238 listings had been analysed. That is not a rendering gap of the B-098 kind — there is
 * no hidden data on the wire — it is work that has not been done, and this button is the only
 * thing that does it.
 *
 * What it costs. Roughly two paced broker calls and three NSE calls per listing, 5–20 seconds.
 * The server refuses it 09:40–10:15 and from 14:00 to the close on a trading day and returns the
 * reason in the body; `api.js post()` throws that text verbatim, and it is shown as-is rather
 * than flattened to "failed" (Gotcha 60).
 */
import { post } from './api.js';
import { el } from './ui.js';

/**
 * @param {object}   row      the listing; needs `symbol`, and `lastAnalysedAt` to pick the label
 * @param {Function} [onDone] called after a successful run; defaults to reloading the page,
 *                            because both callers render from data fetched at load
 */
export function analyseButton(row, onDone) {
  const wrap = el('span');
  const label = row.lastAnalysedAt ? 'Re-analyse' : 'Analyse';

  const btn = el('button.action.secondary.compact', {
    type: 'button',
    title: 'Fetches price history since listing, the first results and shareholding from NSE, and '
      + '— past six months — a screening score. 5–20 seconds, and it goes out to NSE and your '
      + 'broker. The result is stored on this row, so it only has to be done once.',
  }, label);

  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = 'Working…';
    wrap.querySelectorAll('.watch-error').forEach((n) => n.remove());
    try {
      await post(`/api/ipo/analyse?symbol=${encodeURIComponent(row.symbol)}`, undefined, 90000);
      if (onDone) onDone();
      else window.location.reload();
    } catch (err) {
      btn.disabled = false;
      btn.textContent = label;
      const msg = String(err && err.message ? err.message : err);
      wrap.append(el('div.watch-error', { title: msg }, msg.length > 90 ? `${msg.slice(0, 87)}…` : msg));
    }
  });

  wrap.append(btn);
  return wrap;
}

/** The column, so both tables declare it the same way. */
export const analyseCol = () => ({
  key: 'analyse',
  label: '',
  sortable: false,
  render: (r) => analyseButton(r),
});
