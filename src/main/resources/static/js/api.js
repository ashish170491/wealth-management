/**
 * The ONLY place the UI talks to the backend.
 *
 * Everything odd about this API is absorbed here so screens stay dumb:
 *  - four different status conventions across controllers ("SUCCESS" / "success" /
 *    {success:true} / {status:"ok"})
 *  - /api/performance/summary returns percentages as PRE-FORMATTED STRINGS ("12.34")
 *  - the server is only up ~09:00-15:45 on weekdays (SPEC section 27.7), so fetch failure
 *    is the normal case, not an exception
 *
 * No screen may call `fetch` directly.
 */

const TIMEOUT_MS = 8000;
const CACHE_PREFIX = 'dash:';

/** Raised when the server could not be reached at all (as opposed to returning an error). */
export class OfflineError extends Error {
  constructor(path) {
    super(`Could not reach the app (${path})`);
    this.name = 'OfflineError';
    this.path = path;
  }
}

/** Listeners notified when reachability changes, so the banner lives in one place. */
const listeners = new Set();
let online = null;          // null = not yet known
let downReason = null;      // 'timeout' | 'unreachable' - only meaningful while online === false

export function onConnectionChange(fn) {
  listeners.add(fn);
  if (online !== null) fn(online, downReason);
}

/**
 * B-086. `reason` exists because a request that TIMED OUT and a server that is NOT THERE are
 * different facts, and telling the investor to run start-app.bat when the app is already
 * running sends them to fix something that is not broken. This is the same mistake the 4xx/5xx
 * branch in `get()` was written to avoid; the timeout path simply never got the same care.
 *
 * Seen live: the app starts at 09:00 by scheduled task, the DispatcherServlet used to build
 * itself on the first request, and that request took 12.4 s against an 8 s timeout - so the
 * first page load of the day always claimed the app was down.
 */
function setOnline(next, reason = null) {
  if (online === next && downReason === reason) return;
  online = next;
  downReason = next ? null : reason;
  listeners.forEach((fn) => {
    try { fn(next, downReason); } catch (e) { console.error('connection listener failed', e); }
  });
}

// ------------------------------------------------------------------- caching

/**
 * Last-known-good responses, so a screen can still render something after the 15:45
 * shutdown instead of showing a spinner forever. Best-effort: private windows and
 * quota-exceeded both throw, and neither is worth failing a page load over.
 */
function cacheWrite(path, data) {
  try {
    localStorage.setItem(CACHE_PREFIX + path, JSON.stringify({ ts: Date.now(), data }));
  } catch (e) {
    /* storage unavailable or full — the UI works without it */
  }
}

function cacheRead(path) {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + path);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

// ---------------------------------------------------------------- normalizing

/**
 * Unwraps the four status conventions in use across controllers, and surfaces a
 * server-reported failure as a thrown Error rather than a silently empty screen.
 */
function normalize(payload) {
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return payload;

  const status = payload.status ?? payload.success;
  if (status !== undefined) {
    const ok = status === true
      || String(status).toUpperCase() === 'SUCCESS'
      || String(status).toUpperCase() === 'OK';
    if (!ok && (payload.message || payload.error)) {
      throw new Error(payload.message || payload.error);
    }
  }
  return payload;
}

/**
 * Coerces numeric-looking strings to numbers on the given keys.
 *
 * /api/performance/summary hands back `String.format("%.2f", ...)` output, so charting or
 * comparing those fields without this produces string maths ("12.34" > 9 is false).
 */
export function coerceNumbers(obj, keys) {
  if (!obj || typeof obj !== 'object') return obj;
  for (const k of keys) {
    const v = obj[k];
    if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) {
      obj[k] = Number(v);
    }
  }
  return obj;
}

// -------------------------------------------------------------------- fetching

/**
 * GET a JSON endpoint.
 *
 * @param {string}  path            e.g. '/api/dashboard/summary'
 * @param {object}  opts.fallback   returned instead of throwing when offline with no cache
 * @param {boolean} opts.cache      store/serve last-known-good (default true)
 * @returns {Promise<{data:any, stale:boolean, cachedAt:number|null}>}
 */
export async function get(path, { fallback = undefined, cache = true } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);

  try {
    const res = await fetch(path, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
    });
    clearTimeout(timer);
    setOnline(true);

    if (!res.ok) {
      // A 4xx/5xx means the server IS up; do not mark offline or serve stale data —
      // conflating "endpoint is broken" with "app is down" sends the user to start-app.bat
      // for a problem that restarting will not fix.
      throw new Error(`${res.status} ${res.statusText} from ${path}`);
    }

    const data = normalize(await res.json());
    if (cache) cacheWrite(path, data);
    return { data, stale: false, cachedAt: null };
  } catch (err) {
    clearTimeout(timer);

    const timedOut = err.name === 'AbortError';
    const unreachable = timedOut || err instanceof TypeError;
    if (!unreachable) throw err;

    setOnline(false, timedOut ? 'timeout' : 'unreachable');
    const hit = cache ? cacheRead(path) : null;
    if (hit) return { data: hit.data, stale: true, cachedAt: hit.ts };
    if (fallback !== undefined) return { data: fallback, stale: true, cachedAt: null };
    throw new OfflineError(path);
  }
}

/** Convenience for endpoints returning a list; offline with no cache yields []. */
export async function getList(path) {
  return get(path, { fallback: [] });
}

/**
 * POST that the backend treats as read-only (harvest suggestions, rebalance proposal).
 * Never cached, never fired on page load — always behind an explicit click.
 */
export async function postReadOnly(path) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  try {
    const res = await fetch(path, {
      method: 'POST',
      signal: controller.signal,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    });
    clearTimeout(timer);
    setOnline(true);
    if (!res.ok) throw new Error(`${res.status} ${res.statusText} from ${path}`);
    return normalize(await res.json());
  } catch (err) {
    clearTimeout(timer);
    if (err.name === 'AbortError' || err instanceof TypeError) {
      setOnline(false, err.name === 'AbortError' ? 'timeout' : 'unreachable');
      throw new OfflineError(path);
    }
    throw err;
  }
}

/**
 * POST with a JSON body — the watchlist writes (SPEC 27.8 carve-out, 37). Click-only, never cached.
 *
 * On a non-OK response the server's `reason` is thrown as the error message. The backend
 * refuses with 409/422 that carry an explanation (a Kite crunch window, an unpriceable
 * symbol), and `postReadOnly`'s bare "409 Conflict from …" would hide exactly the text the
 * guard exists to deliver (CLAUDE.md Gotcha 60).
 */
export async function post(path, body, timeoutMs = 30000) {
  return send('POST', path, body, timeoutMs);
}

/** PUT with a JSON body - the thesis editor (SPEC 46.7). Same contract as post(). */
export async function put(path, body, timeoutMs = 30000) {
  return send('PUT', path, body, timeoutMs);
}

/** DELETE - cancelling an accumulation plan. Same contract as post(). */
export async function del(path, timeoutMs = 30000) {
  return send('DELETE', path, undefined, timeoutMs);
}

async function send(method, path, body, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(path, {
      method,
      signal: controller.signal,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    clearTimeout(timer);
    setOnline(true);
    if (!res.ok) {
      let reason = `${res.status} ${res.statusText} from ${path}`;
      try {
        const payload = await res.json();
        if (payload && payload.reason) reason = payload.reason;
      } catch (_) { /* no JSON body — keep the status line */ }
      throw new Error(reason);
    }
    return normalize(await res.json());
  } catch (err) {
    clearTimeout(timer);
    if (err.name === 'AbortError') throw new Error('That took too long and was cancelled.');
    if (err instanceof TypeError) { setOnline(false, 'unreachable'); throw new OfflineError(path); }
    throw err;
  }
}

/**
 * Endpoints that are slow AND safe, fetched only on an explicit click.
 *
 * The allowlist is the point, not decoration: in this API a GET can send an email
 * (/api/research/{symbol}) or run a 30-minute scan (/api/multibagger/screen/tier/*), and
 * SPEC section 27.4 forbids the UI from touching those. Anything routed through here must
 * be verified against that list first.
 */
const SLOW_BUT_SAFE = [
  /^\/api\/research\/levels\//,
  /^\/api\/research\/analyst\//,
  /^\/api\/research\/valuation\//,
  /^\/api\/research\/earnings\//,
  /^\/api\/research\/capital-efficiency\//,
  /^\/api\/research\/shareholding\//,
  /^\/api\/accuracy\/dimension-ic/,
];

export async function getOnDemand(path, timeoutMs = 90000) {
  if (!SLOW_BUT_SAFE.some((re) => re.test(path))) {
    // Refuse loudly rather than quietly firing something expensive or email-sending.
    throw new Error(`Blocked: ${path} is not on the safe on-demand allowlist (SPEC 27.4)`);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(path, { signal: controller.signal, headers: { Accept: 'application/json' } });
    clearTimeout(timer);
    setOnline(true);
    if (!res.ok) throw new Error(`${res.status} ${res.statusText} from ${path}`);
    return normalize(await res.json());
  } catch (err) {
    clearTimeout(timer);
    if (err.name === 'AbortError') throw new Error('That took too long and was cancelled.');
    if (err instanceof TypeError) { setOnline(false, 'unreachable'); throw new OfflineError(path); }
    throw err;
  }
}

/** Fires several GETs together; a failure becomes null rather than sinking the page. */
export async function all(paths) {
  const results = await Promise.all(
    paths.map((p) => get(p, { fallback: null }).catch(() => ({ data: null, stale: true, cachedAt: null })))
  );
  const out = {};
  paths.forEach((p, i) => { out[p] = results[i]; });
  return out;
}
