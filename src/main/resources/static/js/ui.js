/**
 * UI atoms. Every function returns a DOM NODE, never an HTML string.
 *
 * That rule is load-bearing (SPEC section 27.3): string-templating in JS would re-weld data
 * to markup at a new layer — the same defect being fixed on the Java side — and every value
 * set through `textContent` is XSS-safe for free.
 *
 * The class names mirror EmailTemplateService's component vocabulary so the dashboard and
 * the daily emails look like one product.
 */

import { missing, NOT_MEASURED, scoreBand, humanLabel, badgeType } from './format.js';
import { glossify, whatThisMeans } from './glossary.js';
import {
  layoutKey,
  applyOrder,
  loadLayout,
  saveLayout,
  clearLayout,
  isCustomised,
  enableColumnLayout,
  recentlyDragged,
} from './table-layout.js';

/**
 * Terse element builder.
 * @param {string} tag           optionally 'div.card.wide' or 'span#id'
 * @param {object|string} [props] attributes, or text if a string
 */
export function el(tag, props = {}, ...children) {
  const [name, ...rest] = tag.split(/(?=[.#])/);
  const node = document.createElement(name);

  for (const token of rest) {
    if (token[0] === '.') node.classList.add(token.slice(1));
    else if (token[0] === '#') node.id = token.slice(1);
  }

  if (typeof props === 'string' || typeof props === 'number') {
    node.textContent = String(props);
  } else {
    for (const [k, v] of Object.entries(props || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'text') node.textContent = String(v);
      else if (k === 'class') node.className += (node.className ? ' ' : '') + v;
      else if (k === 'html') throw new Error('ui.el: raw html is not allowed — build nodes');
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, String(v));
    }
  }

  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

/**
 * A page section: title, mandatory plain-English intro, then content.
 * The `explain` argument is required by SPEC section 21 rule 1 — sections without one are
 * a bug, so this signature makes it awkward to skip.
 */
export function section(title, explain, ...content) {
  const wrap = el('section.section');
  const heading = el('h2.section-title', {}, title);
  wrap.append(heading);
  if (explain) wrap.append(whatThisMeans(explain));
  wrap.append(...content.flat().filter(Boolean));
  glossify(wrap);
  return wrap;
}

const COLLAPSE_PREFIX = 'dash:collapse:';

function readCollapsed(key) {
  try {
    const raw = localStorage.getItem(COLLAPSE_PREFIX + key);
    return raw === null ? null : raw === '1';
  } catch (e) {
    return null;   // private window, or site data blocked — fall back to the caller's default
  }
}

function writeCollapsed(key, collapsed) {
  try {
    localStorage.setItem(COLLAPSE_PREFIX + key, collapsed ? '1' : '0');
  } catch (e) {
    /* the page works without a remembered choice */
  }
}

/**
 * Makes a section fold away, remembering the reader's choice in this browser (SPEC 27.15).
 *
 * Wrap an already-built section: `collapse(withCount(section(...), n), { key, open })`. The
 * ORDER matters — `withCount` must have run first, because the count pill it puts in the heading
 * is what makes this safe.
 *
 * That count is not decoration. Once sections fold, the heading row IS the navigation, and a
 * collapsed section is a section whose findings the reader cannot see. The count is the only
 * thing standing between "I chose not to look at this" and "I did not know there was anything to
 * look at" — so a collapsible section without one hides exactly what the lane exists to surface,
 * and the count must be derived from the list it sits above (Gotcha 98), not from a filtered
 * subset of it.
 */
export function collapse(sectionNode, { key, open = false } = {}) {
  const title = sectionNode.querySelector('.section-title');
  if (!title) return sectionNode;

  const body = el('div.section-body');
  body.append(...[...sectionNode.childNodes].filter((n) => n !== title));
  sectionNode.append(body);

  const stored = key ? readCollapsed(key) : null;
  let isOpen = stored === null ? open : !stored;

  const caret = el('span.caret', {});
  title.prepend(caret);
  title.classList.add('collapsible');
  title.setAttribute('role', 'button');
  title.setAttribute('tabindex', '0');

  const apply = () => {
    // `hidden`, not a style: the CSP blocks nothing here, but a display rule in app.css would
    // silently win over an inline one and this has to stay legible from the DOM.
    body.hidden = !isOpen;
    caret.textContent = isOpen ? '▾' : '▸';
    title.setAttribute('aria-expanded', String(isOpen));
  };

  const toggle = () => {
    isOpen = !isOpen;
    apply();
    if (key) writeCollapsed(key, !isOpen);
  };

  title.addEventListener('click', toggle);
  title.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  });

  apply();
  return sectionNode;
}

/** Adds a count pill to a section heading, e.g. "Holdings (14)". */
export function withCount(sectionNode, count) {
  const title = sectionNode.querySelector('.section-title');
  if (title) title.append(el('span.count', {}, String(count)));
  return sectionNode;
}

/**
 * KPI card. Pass `tone` explicitly, or 'auto' to colour from the numeric `raw` value.
 * When `raw` is missing the card renders in the "unmeasured" style rather than showing 0.
 */
export function kpi({ label, value, sub, tone = 'neutral', raw }) {
  let cls = tone;
  if (tone === 'auto') {
    cls = missing(raw) ? 'unmeasured' : raw > 0 ? 'positive' : raw < 0 ? 'negative' : 'neutral';
  }
  if (value === NOT_MEASURED) cls = 'unmeasured';

  return el('div.summary-card' + (cls && cls !== 'neutral' ? '.' + cls : ''), {},
    el('div.label', {}, label),
    el('div.value.num', {}, value),
    sub ? el('div.sub', {}, sub) : null);
}

/** Badge with a human label — never renders a raw enum. */
export function badge(value, { type, label } = {}) {
  const t = type || badgeType(value);
  return el('span.badge.' + t, {}, label || humanLabel(value));
}

/** The explicit "we could not measure this" marker. Deliberately looks like nothing else. */
export function unmeasured(reason) {
  return el('span.badge.unmeasured', { title: reason || 'No data available for this field' }, NOT_MEASURED);
}

/** 0-100 score bar, colour-banded. A missing score renders as the unmeasured marker. */
export function scoreBar(score, { width = 64 } = {}) {
  if (missing(score)) return unmeasured('This dimension could not be measured for this stock');
  const outer = el('div.score-bar', { style: `width:${width}px`, title: `${score} out of 100` });
  outer.append(el('span.' + scoreBand(score), { style: `width:${Math.max(0, Math.min(100, score))}%` }));
  return outer;
}

export function alert({ severity = 'info', title, message, meta }) {
  const cls = { URGENT: 'urgent', CRITICAL: 'urgent', HIGH: 'urgent', WARNING: 'warning', MEDIUM: 'warning', INFO: 'info', OPPORTUNITY: 'success' }[String(severity).toUpperCase()] || 'info';
  return el('div.alert.' + cls, {},
    title ? el('div.alert-title', {}, title) : null,
    message ? el('div', {}, message) : null,
    meta ? el('div.meta', {}, meta) : null);
}

/** Designed empty state. Never leave a blank panel — say why it is blank. */
export function empty(title, detail) {
  return el('div.empty', {},
    el('div.empty-title', {}, title),
    detail ? el('div', {}, detail) : null);
}

export function skeleton(count = 4, cls = 'skeleton-card') {
  const grid = el('div.grid.kpis');
  for (let i = 0; i < count; i += 1) grid.append(el('div.skeleton.' + cls));
  return grid;
}

export function card(...content) {
  return el('div.card', {}, ...content.flat().filter(Boolean));
}

/**
 * Sortable table.
 *
 * @param columns [{key, label, align, sortable, value(row), render(row)}]
 *   `value` supplies the sort key (and defaults to row[key]); `render` supplies the cell
 *   node. Keeping them separate is what lets a cell show "not measured" while still sorting
 *   correctly — a null sorts last in both directions instead of pretending to be 0.
 */
/**
 * The one table renderer. Sortable headers, "not measured" for every missing value, and
 * (SPEC §27.9) columns the reader can resize and reorder, remembered per table in their own
 * browser. Pass `layout: false` to opt a table out.
 */
/**
 * Smallest table worth its own text-filter box.
 *
 * Raised from 8 to 15 once the chip bars landed (SPEC 27.13): the chips are the primary control
 * now, and a box over every incidental twelve-row list turned the discovery page into five
 * search boxes. Below this the reader can see every row anyway, and a control that never earns
 * a click still costs attention on every page load.
 */
const MIN_ROWS_FOR_FILTER = 15;

/**
 * @param {object}  [opts.filter]  false to suppress the text filter on a table that does not
 *                                 want one; otherwise it appears once the table is big enough
 */
export function table(columns, rows, {
  sortKey, sortDir = 'desc', emptyMessage, layout = true, filter = true,
} = {}) {
  if (!rows || rows.length === 0) {
    return empty('Nothing to show yet', emptyMessage || 'There is no data for this view.');
  }

  const wrap = el('div.table-wrap');
  const tbl = el('table.data-table');
  const colGroup = el('colgroup');
  const thead = el('thead');
  const headRow = el('tr');
  const tbody = el('tbody');

  let state = { key: sortKey, dir: sortDir };

  // Stable identity per column, fixed from the DECLARED order. Deriving it from the displayed
  // index instead would rename a keyless column every time the reader moved it, and the saved
  // layout would then describe columns that no longer answer to those names.
  const stableKeys = new Map(columns.map((c, i) => [c, String(c.key ?? `col${i}`)]));
  const keyOf = (col) => stableKeys.get(col);
  const storageKey = layout ? layoutKey(columns) : null;
  let saved = storageKey ? loadLayout(storageKey) : null;
  let widths = saved && saved.widths ? { ...saved.widths } : {};
  let ordered = applyOrder(columns, saved ? saved.order : null);

  const valueOf = (col, row) => (col.value ? col.value(row) : row[col.key]);

  let term = '';         // the reader's filter text, lower-cased; '' means show everything
  let shown = rows.length;

  /**
   * Does this rendered row match the filter?
   *
   * The haystack is the row's own RENDERED TEXT plus every declared column's raw value, and it
   * needs to be both. Rendered text is what the reader is looking at and typing back ("Wait for
   * a dip", "Banking"); the raw value is the vocabulary underneath it, which is often the word
   * they actually know ("COMPOUNDER" renders as "Yes"). Matching only one of the two produces a
   * filter that mysteriously fails on half of what is on screen.
   */
  function matches(tr, row) {
    if (!term) return true;
    let hay = tr.textContent;
    for (const c of ordered) {
      // `row[c.key]`, NOT `valueOf(c, row)`. `col.value` is the SORT accessor and for most of the
      // interesting columns it is a rank — compoundingRank returns 0..3, so searching it for the
      // word "compounder" finds a number. The row's own field is where the vocabulary lives.
      if (!c.key) continue;
      const v = row[c.key];
      if (v === null || v === undefined) continue;
      // Some fields arrive as a nested record (the shared buy-timing verdict, the structure
      // read). Stringified, their verdict words join the haystack; left alone they contribute
      // the string "[object Object]" to every row, which matches nothing and hides everything.
      hay += ' ' + (typeof v === 'object' ? JSON.stringify(v) : v);
    }
    return hay.toLowerCase().includes(term);
  }

  function paintBody() {
    const sorted = [...rows];
    const col = ordered.find((c) => c.key === state.key);
    if (col) {
      const mult = state.dir === 'asc' ? 1 : -1;
      sorted.sort((a, b) => {
        const av = valueOf(col, a);
        const bv = valueOf(col, b);
        // Missing values sort last regardless of direction: a blank is not "the smallest".
        const aMiss = missing(av) || av === '';
        const bMiss = missing(bv) || bv === '';
        if (aMiss && bMiss) return 0;
        if (aMiss) return 1;
        if (bMiss) return -1;
        if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * mult;
        return String(av).localeCompare(String(bv)) * mult;
      });
    }

    tbody.replaceChildren();
    shown = 0;
    for (const row of sorted) {
      const tr = el('tr');
      for (const c of ordered) {
        const td = el('td' + (c.align === 'r' ? '.r' : ''));
        const rendered = c.render ? c.render(row) : valueOf(c, row);
        if (rendered instanceof Node) td.append(rendered);
        else if (missing(rendered) || rendered === '') td.append(unmeasured());
        else td.textContent = String(rendered);
        tr.append(td);
      }
      // Rendered first, then tested: the reader's own words are in the rendered cells, so the
      // row has to exist before it can be judged.
      if (!matches(tr, row)) continue;
      shown += 1;
      tbody.append(tr);
    }
    paintFilterNote();

    headRow.querySelectorAll('th').forEach((th) => {
      if (th.dataset.key === state.key) th.setAttribute('aria-sort', state.dir === 'asc' ? 'ascending' : 'descending');
      else th.removeAttribute('aria-sort');
      const arrow = th.querySelector('.arrow');
      if (arrow) arrow.textContent = th.dataset.key === state.key ? (state.dir === 'asc' ? '▲' : '▼') : '▽';
    });
  }

  function paintHead() {
    headRow.replaceChildren();
    colGroup.replaceChildren();
    ordered.forEach((c, i) => {
      colGroup.append(el('col'));
      const th = el('th' + (c.align === 'r' ? '.r' : '') + (c.sortable === false ? '' : '.sortable'), {}, c.label);
      th.dataset.key = keyOf(c);
      // Dragging is invisible until someone tries it, so the affordance is named on hover.
      // Without this the feature exists and nobody finds it.
      if (layout) {
        th.title = (c.sortable === false ? 'Drag to reorder' : 'Click to sort · drag to reorder')
          + ' · drag the right edge to resize';
      }
      if (c.sortable !== false) {
        th.append(el('span.arrow', {}, '▽'));
        th.addEventListener('click', () => {
          // The click that ends a reorder drag must not also re-sort the table (SPEC §27.9).
          if (recentlyDragged()) return;
          if (state.key === c.key) state.dir = state.dir === 'asc' ? 'desc' : 'asc';
          else state = { key: c.key, dir: 'desc' };
          paintBody();
        });
      }
      headRow.append(th);
    });
    // Header labels carry glossary terms like any other copy, and this row is rebuilt on every
    // reorder — after mount() has already run its pass over the page.
    glossify(headRow);
  }

  function persist() {
    if (!storageKey) return;
    saved = { order: ordered.map(keyOf), widths };
    saveLayout(storageKey, saved);
    paintFooter();
  }

  function paintFooter() {
    footer.replaceChildren();
    if (!storageKey || !isCustomised(saved)) return;
    const reset = el('button.linkish', {
      onclick: () => {
        clearLayout(storageKey);
        saved = null;
        widths = {};
        ordered = columns.slice();
        tbl.classList.remove('cols-fixed');
        render();
      },
    }, 'Reset to default');
    footer.append(el('span', {}, 'Column layout is yours — widths and order are saved in this browser. '), reset);
  }

  const footer = el('div.table-layout-note');

  // ------------------------------------------------------------------ filter

  const showFilter = filter !== false && rows.length >= MIN_ROWS_FOR_FILTER;
  const filterNote = el('span.table-filter-note');
  const box = el('input.field.table-filter', {
    type: 'search',
    placeholder: `Filter these ${rows.length} rows…`,
    'aria-label': 'Filter the rows of this table',
    title: 'Type any part of a name, sector, verdict or number. Matching is over what you can '
      + 'see in the row and the wording underneath it, so both "Yes" and "compounder" find the '
      + 'same rows.',
  });
  const clearBtn = el('button.linkish', { type: 'button' }, 'Clear');
  clearBtn.addEventListener('click', () => { box.value = ''; setTerm(''); box.focus(); });

  /**
   * What the filter is hiding, always stated.
   *
   * A filtered table that does not say so is a table quietly misrepresenting the universe it was
   * built from — the reader sees 12 rows where the section heading says 284 and has no way to
   * tell a narrow filter from a thin market. Same discipline as a coverage line (Gotcha 44).
   */
  function paintFilterNote() {
    if (!showFilter) return;
    filterNote.replaceChildren();
    if (!term) return;
    // Worded as ROWS matching a TERM, never as "showing N of M". Some pages carry their own
    // count above the table (the screener's chips say "279 of 279 stocks shown"), and two
    // counts phrased alike invite the reader to work out which one is lying. Neither is: they
    // answer different questions, so they have to be worded differently.
    filterNote.append(
      shown === 0
        ? el('span', {}, `No rows match “${term}”. `)
        : el('span', {}, `${shown} of ${rows.length} rows match “${term}” — `),
      clearBtn);
  }

  let debounce = null;
  function setTerm(next) {
    term = String(next || '').trim().toLowerCase();
    // Only the body is repainted. Re-running render() would re-arm the column-layout drag
    // handlers on every keystroke and, under a saved layout, re-enter fixed layout (SPEC 27.9).
    paintBody();
  }
  box.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => setTerm(box.value), 120);
  });
  // Escape clears rather than leaving the reader staring at a table they have narrowed and
  // forgotten they narrowed.
  box.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { box.value = ''; clearTimeout(debounce); setTerm(''); }
  });

  const filterBar = showFilter
    ? el('div.table-filter-bar', {}, box, filterNote)
    : null;

  function render() {
    paintHead();
    paintBody();
    if (layout) {
      enableColumnLayout({
        table: tbl,
        headRow,
        colGroup,
        keys: ordered.map(keyOf),
        widths,
        onResize: persist,
        onReorder: (order) => {
          ordered = applyOrder(columns, order);
          render();
          persist();
        },
      });
    }
    paintFooter();
  }

  thead.append(headRow);
  tbl.append(colGroup, thead, tbody);
  wrap.append(tbl);
  render();
  // The bar sits OUTSIDE .table-wrap on purpose: that element is the horizontal-scroll
  // container, and a filter box inside it would slide off-screen on any table wide enough to
  // need scrolling — which is exactly the table most likely to be filtered.
  return el('div.table-block', {}, filterBar, wrap, footer);
}

/** Warning shown above anything that costs real time or money to run. */
export function costNote(text) {
  return el('div.cost-note', {}, text);
}

/** Renders `nodes` into `mount`, replacing a skeleton. */
export function mount(target, ...nodes) {
  const host = typeof target === 'string' ? document.getElementById(target) : target;
  if (!host) return;
  host.replaceChildren(...nodes.flat().filter(Boolean));
  glossify(host);
}
