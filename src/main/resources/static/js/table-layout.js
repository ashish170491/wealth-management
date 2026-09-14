/**
 * Column resizing and reordering for `table()` in ui.js (SPEC §27.9).
 *
 * Every dashboard table goes through one helper, so the behaviour is wired once here rather than
 * at the eighteen call sites. The layout a reader arranges is theirs and is remembered per table
 * in `localStorage`; it is a display preference and never reaches the server.
 *
 * <h2>Three things worth not getting wrong</h2>
 * 1. **A drag must not sort.** Headers already sort on click, and a click fires after every
 *    pointerup — including the one that ends a drag. `recentlyDragged()` gives ui.js a way to
 *    ignore that click, or every reorder would silently re-sort the table underneath it.
 * 2. **Widths only work under `table-layout: fixed`,** where a width is an instruction rather
 *    than a suggestion. Switching a table into fixed layout redistributes *every* column, so the
 *    first resize measures what the browser had already chosen and pins all of them — otherwise
 *    dragging one edge would visibly reshuffle columns the reader never touched.
 * 3. **A saved layout must not outlive the columns it describes.** The storage key is derived
 *    from the column keys themselves, so adding or removing a column yields a different key and
 *    the stale arrangement is simply never read (rather than being applied to the wrong columns).
 */

/**
 * Bumped whenever the CSS that sizes cells changes, because a saved width is only meaningful
 * under the layout regime it was measured in.
 *
 * `v2` (2026-09-01): cells wrap (SPEC §27.10). Widths saved under the old `white-space: nowrap`
 * rules were measured when every cell was on one line, so they are far wider than the same
 * column needs now — and because a restored layout re-enters `table-layout: fixed` with explicit
 * `<col width>`, those stale widths override the wrapping entirely and the table scrolls
 * sideways forever. Measured on the watchlist: 1950px needed against 1527 available. Changing
 * the key retires them; a new layout is saved the next time the reader drags something.
 */
const LAYOUT_SCHEMA = 'v2';

/** Below this a column is unreadable; above it, a single column can still be dragged very wide. */
const MIN_WIDTH_PX = 44;
const MAX_WIDTH_PX = 900;

/** A click landing within this long of a drag ending belongs to the drag, not to sorting. */
const CLICK_SUPPRESS_MS = 300;

let lastDragEndAt = 0;

/** True when a pointer gesture that just ended was a drag, so its click should be ignored. */
export function recentlyDragged() {
  return Date.now() - lastDragEndAt < CLICK_SUPPRESS_MS;
}

// ------------------------------------------------------------------ pure helpers

/**
 * Storage key for a table's layout: the page plus its column keys, order-independent.
 *
 * Sorted, because the reader's own arrangement must not change the key that stores it. Derived
 * rather than hand-assigned so no call site has to remember to pass an id — and so a table whose
 * columns change gets a new key and starts clean.
 */
export function layoutKey(columns, page) {
  const keys = columns.map((c, i) => String(c.key ?? `col${i}`)).sort();
  const path = page || (typeof location !== 'undefined' ? location.pathname : '');
  const file = String(path).split('/').pop() || 'index';
  let hash = 0;
  const seed = keys.join('|');
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  return `dash.cols.${LAYOUT_SCHEMA}.${file}.${(hash >>> 0).toString(36)}`;
}

/**
 * Columns in the reader's order.
 *
 * Keys the saved order does not mention are appended in their declared order, and keys it names
 * that no longer exist are dropped — so a partially-stale layout degrades to "mostly yours"
 * instead of throwing away either the preference or a column.
 */
export function applyOrder(columns, order) {
  if (!Array.isArray(order) || order.length === 0) return columns.slice();
  const byKey = new Map(columns.map((c, i) => [String(c.key ?? `col${i}`), c]));
  const out = [];
  for (const key of order) {
    const col = byKey.get(String(key));
    if (col && !out.includes(col)) out.push(col);
  }
  for (const col of columns) if (!out.includes(col)) out.push(col);
  return out;
}

/** `order` with `key` lifted out and reinserted at `toIndex` (measured in the resulting array). */
export function moveKey(order, key, toIndex) {
  const from = order.indexOf(key);
  if (from < 0) return order.slice();
  const rest = order.slice(0, from).concat(order.slice(from + 1));
  const at = Math.max(0, Math.min(rest.length, toIndex));
  rest.splice(at, 0, key);
  return rest;
}

export function clampWidth(px) {
  if (!Number.isFinite(px)) return MIN_WIDTH_PX;
  return Math.max(MIN_WIDTH_PX, Math.min(MAX_WIDTH_PX, Math.round(px)));
}

// ------------------------------------------------------------------ persistence

/**
 * Reading and writing both swallow their errors: storage throws in private windows and when a
 * browser is set to block site data, and a table that will not render because a preference could
 * not be read would be a far worse failure than losing the preference.
 */
export function loadLayout(key) {
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    return {
      order: Array.isArray(parsed.order) ? parsed.order.map(String) : [],
      widths: parsed.widths && typeof parsed.widths === 'object' ? parsed.widths : {},
    };
  } catch (e) {
    return null;
  }
}

export function saveLayout(key, layout) {
  try {
    window.localStorage.setItem(key, JSON.stringify({
      order: layout.order || [],
      widths: layout.widths || {},
    }));
  } catch (e) {
    /* preference lost, table unaffected */
  }
}

export function clearLayout(key) {
  try {
    window.localStorage.removeItem(key);
  } catch (e) {
    /* nothing to do */
  }
}

export function isCustomised(layout) {
  if (!layout) return false;
  return (layout.order && layout.order.length > 0)
      || (layout.widths && Object.keys(layout.widths).length > 0);
}

// ------------------------------------------------------------------ DOM wiring

/**
 * Attaches resize handles and reorder dragging to a rendered header row.
 *
 * @param opts.table     the <table> element
 * @param opts.headRow   the header <tr>, already populated with <th data-key>
 * @param opts.colGroup  the <colgroup>, one <col> per column in current order
 * @param opts.keys      column keys in current display order
 * @param opts.widths    { key: px } — mutated in place as the reader drags
 * @param opts.onResize  called with the widths object once a resize settles
 * @param opts.onReorder called with the new key order once a move settles
 */
export function enableColumnLayout({ table, headRow, colGroup, keys, widths, onResize, onReorder }) {
  const ths = Array.from(headRow.querySelectorAll('th'));

  /**
   * Pin every column at the width the browser already chose, then switch to fixed layout.
   * Done on the first drag rather than at render time so a table nobody touches keeps the
   * content-driven sizing that suits it.
   */
  function ensureFixed() {
    if (table.classList.contains('cols-fixed')) return;
    const measured = ths.map((th) => th.getBoundingClientRect().width);
    ths.forEach((th, i) => {
      const key = th.dataset.key;
      if (!(key in widths)) widths[key] = clampWidth(measured[i]);
    });
    applyWidths();
    table.classList.add('cols-fixed');
  }

  function applyWidths() {
    const cols = Array.from(colGroup.children);
    cols.forEach((col, i) => {
      const key = keys[i];
      const w = widths[key];
      col.style.width = w ? `${w}px` : '';
    });
  }

  // ---- resize

  for (const th of ths) {
    const handle = document.createElement('span');
    handle.className = 'col-resize';
    handle.setAttribute('aria-hidden', 'true');
    handle.title = 'Drag to resize this column';
    th.appendChild(handle);

    handle.addEventListener('pointerdown', (ev) => {
      // Without this the header would also start a reorder drag, and the column would both
      // resize and move.
      ev.preventDefault();
      ev.stopPropagation();

      ensureFixed();
      const key = th.dataset.key;
      const startX = ev.clientX;
      const startW = widths[key] || th.getBoundingClientRect().width;
      table.classList.add('cols-dragging');
      handle.setPointerCapture(ev.pointerId);

      const move = (e) => {
        widths[key] = clampWidth(startW + (e.clientX - startX));
        applyWidths();
      };
      const up = () => {
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', up);
        handle.removeEventListener('pointercancel', up);
        table.classList.remove('cols-dragging');
        lastDragEndAt = Date.now();
        if (onResize) onResize(widths);
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', up);
      handle.addEventListener('pointercancel', up);
    });
  }

  // ---- reorder

  for (const th of ths) {
    th.addEventListener('pointerdown', (ev) => {
      if (ev.button !== 0) return;
      const key = th.dataset.key;
      if (!key) return;

      const startX = ev.clientX;
      let dragging = false;
      let targetIndex = null;

      const clearMarks = () => ths.forEach((t) => t.classList.remove('drop-before', 'drop-after'));

      const move = (e) => {
        if (!dragging) {
          // A few pixels of slop, so a slightly imprecise click still sorts rather than
          // starting a drag nobody asked for.
          if (Math.abs(e.clientX - startX) < 5) return;
          dragging = true;
          th.classList.add('col-dragging');
          table.classList.add('cols-dragging');
          try { th.setPointerCapture(e.pointerId); } catch (err) { /* capture is a nicety */ }
        }

        clearMarks();
        targetIndex = null;
        for (let i = 0; i < ths.length; i++) {
          const rect = ths[i].getBoundingClientRect();
          if (e.clientX >= rect.left && e.clientX <= rect.right) {
            const before = e.clientX < rect.left + rect.width / 2;
            ths[i].classList.add(before ? 'drop-before' : 'drop-after');
            targetIndex = before ? i : i + 1;
            break;
          }
        }
      };

      const up = () => {
        th.removeEventListener('pointermove', move);
        th.removeEventListener('pointerup', up);
        th.removeEventListener('pointercancel', up);
        table.classList.remove('cols-dragging');
        th.classList.remove('col-dragging');
        clearMarks();
        if (!dragging) return;
        lastDragEndAt = Date.now();

        if (targetIndex === null) return;
        const from = keys.indexOf(key);
        // Removing the dragged column shifts everything after it one place left.
        const to = targetIndex > from ? targetIndex - 1 : targetIndex;
        if (to === from) return;
        if (onReorder) onReorder(moveKey(keys, key, to));
      };

      th.addEventListener('pointermove', move);
      th.addEventListener('pointerup', up);
      th.addEventListener('pointercancel', up);
    });
  }

  applyWidths();
  if (Object.keys(widths).length > 0) table.classList.add('cols-fixed');
}
