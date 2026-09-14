/**
 * "+ Watch" button for any table that lists stocks (SPEC 37.6).
 *
 * Load-time cost: one DB-only GET (/api/watchlist/items) to know which symbols are already
 * tracked. The click itself is the sanctioned write (POST /api/watchlist/items): it prices the
 * stock live, so it is button-only and the server refuses it from 14:55 with a reason — which
 * post() throws verbatim and this button shows inline.
 */

import { get, post } from './api.js';
import { el } from './ui.js';
import { symbolKey } from './format.js';

/** Symbols currently on the watchlist (active rows). Empty set when the app is unreachable. */
export async function loadWatchedSet() {
  try {
    const r = await get('/api/watchlist/items', { fallback: { items: [] }, cache: false });
    const items = (r.data && r.data.items) || [];
    return new Set(items.filter((w) => w.active).map((w) => symbolKey(w.symbol)));
  } catch (_) {
    return new Set();
  }
}

/**
 * A compact button: "+ Watch" when not tracked, "✓ Watching" (link) when it is. On success the
 * symbol is added to `watched` so every other row rendered from the same set stays consistent.
 */
export function watchButton(symbol, watched, { note } = {}) {
  const key = symbolKey(symbol);
  const wrap = el('span.watch-cell');

  function paintWatching() {
    wrap.replaceChildren(el('a.watching', { href: 'watchlist.html', title: 'Already on your watchlist — open it' }, '✓ Watching'));
  }

  if (watched && watched.has(key)) {
    paintWatching();
    return wrap;
  }

  const btn = el('button.action.secondary.compact', { title: 'Add to your watchlist — records today’s date and price so you can see how it does from here' }, '+ Watch');
  btn.addEventListener('click', async (e) => {
    e.preventDefault();
    btn.disabled = true;
    btn.textContent = 'Adding…';
    try {
      await post('/api/watchlist/items', { symbol, note: note || null });
      if (watched) watched.add(key);
      paintWatching();
    } catch (err) {
      btn.disabled = false;
      btn.textContent = '+ Watch';
      const msg = String(err && err.message ? err.message : err);
      const already = /already on the watchlist/i.test(msg);
      if (already) { if (watched) watched.add(key); paintWatching(); return; }
      wrap.querySelectorAll('.watch-error').forEach((n) => n.remove());
      wrap.append(el('div.watch-error', { title: msg }, msg.length > 90 ? msg.slice(0, 87) + '…' : msg));
    }
  });
  wrap.append(btn);
  return wrap;
}
