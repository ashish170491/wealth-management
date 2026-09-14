/**
 * Predefined filter chips — the screener's pattern, made shareable (SPEC 27.13).
 *
 * The screener grew a good filter bar and nothing else could use it. This is that bar with its
 * vocabulary lifted out: the MECHANISM lives here (state, chips, counts, the clear control), and
 * every page declares its OWN groups, because "narrow this list" means different things on a
 * screening run, a portfolio and a set of new listings. One mechanism, many vocabularies — the
 * same split as `table()` and `buy-timing.js`, and the reason a fix here reaches every screen.
 *
 * Two rules the chips must obey, both learned elsewhere in this app.
 *
 * A chip must never quietly fold "not measured" into a verdict. "Compounders only" excludes
 * NOT_MEASURED rather than sweeping in businesses whose accounts could not be read, and "Hide
 * red flags" keeps the UNCHECKED visible, because a stock nobody examined is not a stock that
 * passed (Gotcha 21, 44). Where that distinction matters the group offers the third chip
 * explicitly, so the reader can ask for the unexamined ones on purpose.
 *
 * And a narrowed list must say so. The bar prints "29 of 279 stocks shown" beside a Clear
 * control whenever anything is active, because a list filtered down to 29 under a heading that
 * says 279 is a list misrepresenting what it was drawn from.
 */
import { el } from './ui.js';

/**
 * @param {Array}    groups   see the shapes below
 * @param {Function} onChange called when any chip or the search box moves
 *
 * A group is one of:
 *   { key, label, options: [{ value, text, title?, test? }] | (rows) => [...], ownLine? }
 *       a one-of-N choice. The option whose `test` is omitted is the "all" escape hatch, and
 *       it is what the group resets to.
 *   { key, toggle: true, text, title?, test }
 *       an on/off switch, off by default.
 *   { search: true, placeholder?, fields? }
 *       a text box. `fields` lists row keys to match; omitted, it matches every scalar field
 *       on the row, which is what a reader typing a ticker expects.
 */
export function chipFilters(groups, onChange) {
  const state = {};

  const optionsOf = (g, rows) => (typeof g.options === 'function' ? g.options(rows || []) : g.options);

  function firstValueOf(g) {
    const opts = typeof g.options === 'function' ? [] : g.options;
    return opts.length ? opts[0].value : 'ALL';
  }

  function reset() {
    for (const g of groups) {
      if (g.search) state.__search = '';
      else if (g.toggle) state[g.key] = false;
      else state[g.key] = firstValueOf(g);
    }
  }
  reset();

  function testOf(g, rows) {
    if (g.toggle) return state[g.key] ? g.test : null;
    const opt = optionsOf(g, rows).find((o) => o.value === state[g.key]);
    return opt && opt.test ? opt.test : null;
  }

  function searchHay(row, fields) {
    if (fields) return fields.map((f) => row[f]).filter((v) => v !== null && v !== undefined).join(' ');
    let hay = '';
    for (const [, v] of Object.entries(row)) {
      if (v === null || v === undefined) continue;
      if (typeof v === 'object') continue;   // nested records are noise in a ticker search
      hay += ' ' + v;
    }
    return hay;
  }

  /** Apply every active chip. Rows are passed in so data-dependent groups can resolve. */
  function apply(rows) {
    const active = groups.filter((g) => !g.search)
      .map((g) => testOf(g, rows)).filter(Boolean);
    const q = String(state.__search || '').trim().toLowerCase();
    const searchGroup = groups.find((g) => g.search);

    return rows.filter((r) => {
      for (const t of active) if (!t(r)) return false;
      if (q && searchGroup && !searchHay(r, searchGroup.fields).toLowerCase().includes(q)) return false;
      return true;
    });
  }

  function anyActive(rows) {
    if (String(state.__search || '').trim()) return true;
    // Boolean, not `!== null`: a display-only toggle (the screener's "show the seven scores")
    // lives in this bar and carries no `test`, and it must not make the page claim it is
    // filtered when it has only changed which columns are drawn.
    return groups.some((g) => !g.search && Boolean(testOf(g, rows)));
  }

  /**
   * @param {Array}  rows  every row BEFORE filtering — the denominator, and what data-dependent
   *                       option lists are built from
   * @param {number} shown how many survived
   * @param {string} noun  what the rows are, in the reader's words ("stocks", "listings")
   */
  function bar(rows, shown, noun = 'stocks') {
    const wrap = el('div', {});
    const main = el('div.filters');
    const trailing = [];

    const chip = (key, value, text, { toggle = false, title } = {}) => {
      const pressed = toggle ? !!state[key] : state[key] === value;
      const c = el('button.chip', {
        type: 'button', 'aria-pressed': String(pressed), title: title || null,
      }, text);
      c.addEventListener('click', () => {
        state[key] = toggle ? !state[key] : value;
        onChange();
      });
      return c;
    };

    for (const g of groups) {
      if (g.search) {
        const box = el('input.field', {
          type: 'search',
          placeholder: g.placeholder || 'Search a stock…',
          value: state.__search,
          'aria-label': g.placeholder || 'Search these rows',
        });
        box.addEventListener('input', () => {
          state.__search = box.value;
          onChange({ keepFocus: box });
        });
        trailing.push(el('div.filters', { style: 'margin-top:-6px;align-items:center' }, box));
        continue;
      }

      const host = g.ownLine ? el('div.filters', { style: 'margin-top:-6px' }) : main;
      if (g.label) {
        host.append(el('span.muted', {
          style: 'font-size:12.5px;align-self:center;margin-right:2px',
        }, g.label));
      }
      if (g.toggle) {
        // A toggle with a test earns a count for the same reason an option does: it says what
        // clicking it will leave you looking at. A display-only toggle has no test and no count.
        const n = g.test ? ` (${rows.filter(g.test).length})` : '';
        host.append(chip(g.key, true, g.text + n, { toggle: true, title: g.title }));
      } else {
        for (const o of optionsOf(g, rows)) {
          // Every choosable chip carries how many rows it will actually give you.
          //
          // Without it a chip is a promise the data may not keep: "Core" on a portfolio whose
          // holdings are all satellite today looks like a filter that broke, and the reader has
          // no way to tell an empty result from a bug. The count also does the page's own
          // reporting for free — "Compounders only (35)" answers the question before the click.
          // Options carrying their own count in the text (the sector list) are left alone.
          const n = o.test && !/\(\d+\)$/.test(o.text)
            ? ` (${rows.filter(o.test).length})`
            : '';
          host.append(chip(g.key, o.value, o.text + n, { title: o.title }));
        }
      }
      if (g.ownLine) trailing.push(host);
    }

    if (main.childNodes.length) wrap.append(main);
    for (const n of trailing) wrap.append(n);

    // The count, and the way back. Shown only while something is actually narrowing the list:
    // "279 of 279" on every page load is noise, and noise trains the reader to stop reading the
    // line that matters when it finally says something.
    if (anyActive(rows)) {
      const clear = el('button.linkish', { type: 'button' }, 'Clear filters');
      clear.addEventListener('click', () => { reset(); onChange(); });
      wrap.append(el('div.filter-count', {},
        el('span', {}, `${shown} of ${rows.length} ${noun} shown — `), clear));
    }

    return wrap;
  }

  return { state, apply, bar, reset, anyActive };
}

/** Sector chips with counts, commonest first. Shared because three pages want the same list. */
export function sectorOptions(rows, { key = 'sector', label } = {}) {
  const counts = new Map();
  for (const r of rows) {
    const k = r[key] || 'UNCLASSIFIED';
    counts.set(k, (counts.get(k) || 0) + 1);
  }
  const named = [...counts.entries()].filter(([k]) => k !== 'UNCLASSIFIED')
    .sort((a, b) => b[1] - a[1]);
  // Unclassified goes last and is named, never hidden: it is a gap in the data, not a sector
  // (Gotcha 110 — a placeholder is never a bucket).
  if (counts.has('UNCLASSIFIED')) named.push(['UNCLASSIFIED', counts.get('UNCLASSIFIED')]);

  const name = label || ((k) => k);
  return [{ value: 'ALL', text: 'All' }, ...named.map(([k, n]) => ({
    value: k,
    text: `${k === 'UNCLASSIFIED' ? 'Not classified' : name(k)} (${n})`,
    test: (r) => (r[key] || 'UNCLASSIFIED') === k,
  }))];
}
