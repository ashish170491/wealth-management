/**
 * Policy-backed themes — the one renderer for every surface (SPEC 51.5).
 *
 * The tags are decided server-side by universe-themes.csv and arrive on a row as `themes` plus
 * three companions. Nothing is decided here: this file picks a wording and a colour, so the map
 * stays in one readable place — the same split as macro-cells.js and compounding.js, and the
 * reason the portfolio, the screener, discovery, the watchlist and the Themes page cannot end up
 * describing one stock's theme differently (Gotcha 85).
 *
 * Four things this must never do.
 *
 * An EMPTY list and a MISSING one must never render alike. `themes: []` means the map was
 * consulted and names no tracked theme for this business — an ordinary finding, and the answer
 * for most of the market. A missing key means the lookup never ran, and that has to draw the
 * unmeasured marker. Collapsing them lets a blind spot read as an all-clear (Gotcha 21, 44, 121).
 *
 * It must not present itself as a score, a rating or a reason to own anything. A theme is a
 * demand signal with a political dependency; the seven pillars still decide whether the business
 * is any good, and SPEC 51.1 makes the zero-points rule structural rather than a promise.
 *
 * It must not render the catalogue name. `AI_DATA_CENTRES` is an internal token; the investor
 * reads "AI and data centres", which the server sends as `themeLabels`.
 *
 * And a theme badge is never coloured by sentiment. Every theme gets the same neutral treatment,
 * because a green chip beside "Semiconductors" is a recommendation wearing a colour.
 */
import { el, badge, unmeasured } from './ui.js';

/** Catalogue name -> the short form a narrow column can hold. Labels come from the server. */
export const THEME_SHORT = {
  SEMICONDUCTORS: 'Semis',
  ELECTRONICS_EMS: 'Electronics',
  AI_DATA_CENTRES: 'AI/DC',
  WATER_INFRASTRUCTURE: 'Water',
  DEFENCE_INDIGENISATION: 'Defence',
  RAILWAY_MODERNISATION: 'Railways',
  SOLAR_MANUFACTURING: 'Solar',
  WIND_ENERGY: 'Wind',
  GREEN_HYDROGEN: 'Hydrogen',
  POWER_TRANSMISSION: 'Grid',
  EV_BATTERY: 'EV',
  PHARMA_API: 'Pharma API',
};

/** Coverage status -> how it reads. Three states, deliberately (SPEC 51.3). */
export const THEME_STATUS = {
  SCREENED: { type: 'success', label: 'Analysed' },
  NOT_SCREENED: { type: 'warning', label: 'Not screened' },
  UNVERIFIED: { type: 'neutral', label: 'Ticker unconfirmed' },
};

/** True when the row carries a theme answer at all — an empty list still counts as an answer. */
export function hasThemeAnswer(row) {
  return !!row && Array.isArray(row.themes);
}

/** Sort: tagged businesses first, then the untagged, with the unmeasured always last. */
export function themeRank(row) {
  if (!hasThemeAnswer(row)) return 2;
  return row.themes.length ? 0 : 1;
}

function short(name) {
  return THEME_SHORT[name] || name;
}

/**
 * One table cell.
 *
 * The scheme and the role go in the tooltip so the column stays narrow (SPEC 27.10); the full
 * list with its cautions is on the Themes page and the stock page.
 */
export function themeCell(row) {
  if (!hasThemeAnswer(row)) {
    return unmeasured('This screen did not look up themes for this business.');
  }

  const names = row.themes;
  if (!names.length) {
    const node = el('span.faint', {}, '—');
    node.title = 'Checked. No tracked policy-backed theme names this business, which is the '
      + 'ordinary answer — and a different thing from the app not having looked.';
    node.setAttribute('data-no-gloss', '');
    return node;
  }

  const labels = Array.isArray(row.themeLabels) ? row.themeLabels : names;
  const policies = Array.isArray(row.themePolicies) ? row.themePolicies : [];
  const roles = Array.isArray(row.themeRoles) ? row.themeRoles : [];

  const wrap = el('div', { style: 'display:flex;flex-wrap:wrap;gap:3px' });
  names.forEach((name, i) => {
    // Neutral for every theme, always: a coloured chip beside a sector name is a recommendation.
    const node = badge(name, { type: 'neutral', label: short(name) });
    const parts = [labels[i] || name];
    if (policies[i]) parts.push('Policy: ' + policies[i] + '.');
    if (roles[i]) parts.push('Role: ' + roles[i] + '.');
    parts.push('A theme says the government is funding the area. It is not a score, and it says '
      + 'nothing about whether this business is worth owning.');
    node.title = parts.join(' ');
    node.setAttribute('data-no-gloss', '');
    wrap.appendChild(node);
  });
  return wrap;
}

/**
 * The column, declared once.
 *
 * Five screens draw this and the header string lives here alone, because a column reading
 * "Theme" on one screen and "Sector" on another invites the reader to ask whether they are the
 * same classification — they are not. NSE's sector is the exchange's macro bucket; this is a
 * finer, hand-kept, policy-anchored tag (Gotcha 85 applied to a heading).
 */
export function themeCol() {
  return {
    key: 'themes',
    label: 'Theme',
    value: themeRank,
    render: themeCell,
  };
}

/**
 * The chip group.
 *
 * "Not in a theme" gets its own chip rather than falling in with the unmeasured, for the same
 * reason UNCLASSIFIED does on the portfolio tier filter: one is a finding and the other is the
 * absence of one (Gotcha 117 rule b).
 */
export const themeFilterGroup = {
  label: 'Theme:',
  key: 'theme',
  // A function of the rows, so the chips only offer themes actually present in THIS list. A chip
  // that matches nothing is a promise the data does not keep (Gotcha 117 rule a).
  options: (rows) => {
    const present = new Set();
    (rows || []).forEach((r) => {
      if (Array.isArray(r.themes)) r.themes.forEach((t) => present.add(t));
    });

    const options = [{ value: 'ALL', text: 'All' }];
    Object.keys(THEME_SHORT).filter((t) => present.has(t)).forEach((t) => {
      options.push({
        value: t,
        text: THEME_SHORT[t],
        test: (r) => Array.isArray(r.themes) && r.themes.includes(t),
      });
    });
    options.push({
      value: 'NONE',
      text: 'Not in a theme',
      test: (r) => Array.isArray(r.themes) && r.themes.length === 0,
      title: 'Checked, and no tracked policy-backed theme names the business. Most of the market.',
    });
    return options;
  },
};

/**
 * The coverage line that has to sit under any table drawing this column.
 *
 * Without it a mostly-empty Theme column reads as "these stocks are in no funded theme", when
 * what it may mean is that the map is a hand-kept list nobody has extended (Gotcha 44). It names
 * the denominator and links to the screen that shows what is missing.
 */
export function themeCoverageLine(rows, noun) {
  const all = rows || [];
  if (!all.length) return null;
  const answered = all.filter(hasThemeAnswer).length;
  const tagged = all.filter((r) => hasThemeAnswer(r) && r.themes.length).length;
  const what = noun || 'stocks';

  return el('div.muted', { style: 'font-size:12.5px;margin-top:10px' },
    tagged + ' of ' + answered + ' ' + what + ' sit in a policy-backed theme this app tracks'
    + (answered < all.length
      ? ' (' + (all.length - answered) + ' not looked up on this screen)' : '')
    + '. The theme map is hand-kept and is not a complete census of any sector, so a business '
    + 'nobody has added is invisible to this count. A theme means the government is funding the '
    + 'area — never that a business is worth owning. ',
    el('a', { href: 'themes.html' }, 'What is covered, and what is missing'));
}
