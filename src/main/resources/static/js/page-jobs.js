/**
 * Jobs — every scheduled job, when it last ran, how long it took, and whether anything was lost
 * (SPEC 56).
 *
 * Three DB-only requests, no broker or NSE call (Gotcha 17, 39). Read in this order: what needs
 * attention, what is running, every job, what went wrong, then the day's timeline and one job's
 * history.
 *
 * Two things this page must never do, because the obvious version of it does both:
 *   1. Call a run "succeeded" because it did not throw. Almost every job here catches its own
 *      exception and logs it, so the server counts ERROR lines on the job's thread and reports
 *      COMPLETED_WITH_ERRORS — that badge is drawn as a problem, never as a success.
 *   2. Call a fire "missed" because there is no row. The app is stopped and started by Windows
 *      tasks, so most fires with no row fell while it was down. Those are counted separately and
 *      drawn grey, and only a fire lost while the app was demonstrably up is red.
 */

import { initChrome, registerRefresh } from './nav.js';
import { el, section, card, table, kpi, mount, empty, withCount, withSummary, badge } from './ui.js';
import { get } from './api.js';
import { dateTimeIst, shortDate, NOT_MEASURED } from './format.js';

const view = document.getElementById('view');
const WINDOW_DAYS = 14;

const OUTCOME = {
  SUCCEEDED: { label: 'Succeeded', type: 'success' },
  COMPLETED_WITH_ERRORS: { label: 'Finished with errors', type: 'danger' },
  FAILED: { label: 'Failed', type: 'danger' },
  SKIPPED: { label: 'Skipped', type: 'neutral' },
  INTERRUPTED: { label: 'Never finished', type: 'danger' },
  RUNNING: { label: 'Running', type: 'info' },
  MISSED: { label: 'Missed', type: 'danger' },
  OVERLAPPED: { label: 'Swallowed by overrun', type: 'warning' },
};

const VERDICT = {
  PROBLEM: { label: 'Problem', type: 'danger', rank: 0 },
  WATCH: { label: 'Worth a look', type: 'warning', rank: 1 },
  NO_HISTORY: { label: 'No run yet', type: 'neutral', rank: 2 },
  OK: { label: 'OK', type: 'success', rank: 3 },
  NOT_DUE: { label: 'Not due yet', type: 'neutral', rank: 4 },
};

/** Bar colours on the timeline, from the palette app.css defines (Gotcha 104: no --accent). */
const BAR = {
  SUCCEEDED: 'var(--profit)',
  COMPLETED_WITH_ERRORS: 'var(--loss)',
  FAILED: 'var(--loss)',
  INTERRUPTED: 'var(--loss-deep)',
  RUNNING: 'var(--info)',
  SKIPPED: 'var(--ink-faint)',
};

let overview = null;
let timelineDate = null;     // null = the latest day with runs
let historyJob = null;

// ------------------------------------------------------------------ formatting

/** 850 -> "850 ms", 12300 -> "12.3 s", 245000 -> "4 min 5 s", 3960000 -> "1 h 6 min". */
function dur(ms) {
  if (ms === null || ms === undefined) return NOT_MEASURED;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)} s`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = Math.floor(s % 60);
  return h > 0 ? `${h} h ${m} min` : `${m} min ${sec} s`;
}

/** Start delay. Under five seconds is the scheduler doing its job and is said as "on time". */
function lag(ms) {
  if (ms === null || ms === undefined) return NOT_MEASURED;
  return ms < 5000 ? 'on time' : `${dur(ms)} late`;
}

function parseLocal(iso) {
  if (!iso) return null;
  const d = new Date(String(iso).replace(' ', 'T'));
  return Number.isNaN(d.getTime()) ? null : d;
}

function hhmm(iso) {
  const m = String(iso || '').match(/T(\d{2}):(\d{2})/);
  return m ? `${m[1]}:${m[2]}` : '';
}

function outcomeBadge(o) {
  const spec = OUTCOME[o] || { label: o || NOT_MEASURED, type: 'neutral' };
  return badge(o, { label: spec.label, type: spec.type });
}

function verdictBadge(v) {
  const spec = VERDICT[v] || { label: v, type: 'neutral' };
  return badge(v, { label: spec.label, type: spec.type });
}

/**
 * A job that has never done real work has nothing to measure, which is a different fact from a
 * measurement that failed. Drawing "not measured" three times on every row of a young ledger
 * reads as thirty broken jobs (Gotcha 120's distinction: work not yet done, not a gap).
 */
function neverRan(j) {
  return !j.lastRealRun && !j.stats.runsMeasured;
}

const QUIET = () => el('span.faint', { title: 'No real run recorded yet' }, '—');

function sub(text, extra = '') {
  return text ? el('span.cell-sub' + extra, {}, text) : null;
}

/** The sentence that goes with a run's badge — on screen, never in a tooltip (Gotcha 146). */
function runDetail(r) {
  if (!r) return null;
  if (r.outcome === 'COMPLETED_WITH_ERRORS') return r.firstError;
  if (r.outcome === 'SUCCEEDED' && r.warningsLogged) {
    return `${r.warningsLogged} warning${r.warningsLogged === 1 ? '' : 's'} logged`;
  }
  return r.reason;
}

// ------------------------------------------------------------------ sections

function summaryRow(ov) {
  const s = ov.summary;
  const busy = ov.threadsBusy === null || ov.threadsBusy === undefined ? NOT_MEASURED : `${ov.threadsBusy} of ${ov.poolSize}`;
  return el('div.grid.kpis', {},
    kpi({
      label: 'Jobs scheduled',
      value: String(s.jobs),
      sub: 'registered with the scheduler right now',
      tone: 'neutral',
    }),
    kpi({
      label: 'Need attention',
      value: String(s.problem),
      sub: s.problem ? 'last run went wrong, or a fire was lost' : 'nothing is failing',
      tone: s.problem ? 'negative' : 'positive',
    }),
    kpi({
      label: 'Worth a look',
      value: String(s.watch),
      sub: 'recovered, started late, ran slow or switched off',
      tone: s.watch ? 'warning' : 'neutral',
    }),
    kpi({
      label: 'Scheduler threads busy',
      value: busy,
      sub: `${ov.runningNow.length} running now`,
      tone: 'neutral',
    }),
    kpi({
      label: 'History since',
      value: ov.historyStartsAt ? shortDate(ov.historyStartsAt) : NOT_MEASURED,
      sub: ov.appStartedAt ? `this session started ${dateTimeIst(ov.appStartedAt)}` : 'no session recorded',
      tone: 'neutral',
    }));
}

function runningTable(ov) {
  const now = parseLocal(ov.generatedAt);
  const typical = new Map(ov.jobs.map((j) => [j.key, j.stats]));
  return table([
    { key: 'label', label: 'Job', render: (r) => el('span.cell-main', {}, r.label) },
    { key: 'startedAt', label: 'Started', render: (r) => dateTimeIst(r.startedAt) },
    {
      key: 'elapsed',
      label: 'Running for',
      value: (r) => (now && parseLocal(r.startedAt) ? now - parseLocal(r.startedAt) : null),
      render: (r) => {
        const ms = now && parseLocal(r.startedAt) ? now - parseLocal(r.startedAt) : null;
        const st = typical.get(r.jobKey);
        const over = st && st.p90Ms && ms > st.p90Ms;
        return el('span', {}, dur(ms), over ? sub('longer than 9 in 10 of its runs', '.neg') : null);
      },
    },
    {
      key: 'typical',
      label: 'Typical',
      value: (r) => (typical.get(r.jobKey) || {}).medianMs,
      render: (r) => dur((typical.get(r.jobKey) || {}).medianMs),
    },
    { key: 'threadName', label: 'Thread' },
  ], ov.runningNow, { sortKey: 'startedAt', sortDir: 'asc', filter: false });
}

function jobsTable(ov) {
  return table([
    {
      key: 'label',
      label: 'Job',
      render: (j) => el('span', {},
        el('span.cell-main', {}, j.label),
        sub(j.shortKey),
        j.touches ? sub(`Uses: ${j.touches}`) : null),
    },
    {
      key: 'verdict',
      label: 'Health',
      value: (j) => (VERDICT[j.verdict] || {}).rank,
      render: (j) => el('span', {}, verdictBadge(j.verdict), sub((j.reasons || [])[0])),
    },
    {
      key: 'schedule',
      label: 'Schedule',
      value: (j) => j.runsPerYear,
      render: (j) => el('span', {},
        j.schedule,
        sub(j.cron),
        j.outsideWindowOnRunDay
          ? sub(`${j.outsideWindowOnRunDay} of ${j.timesOnRunDay.length} daily fires fall outside 09:15–15:30 and are refused by design`)
          : null),
    },
    {
      key: 'nextRun',
      label: 'Next run',
      render: (j) => (j.nextRun ? dateTimeIst(j.nextRun) : NOT_MEASURED),
    },
    {
      key: 'lastRun',
      label: 'Last fired',
      value: (j) => (j.lastRun ? j.lastRun.startedAt : null),
      render: (j) => (j.lastRun
        ? el('span', {}, dateTimeIst(j.lastRun.startedAt), ' ', outcomeBadge(j.lastRun.outcome))
        : el('span.muted', {}, 'never')),
    },
    {
      key: 'lastReal',
      label: 'Last real run',
      value: (j) => (j.lastRealRun ? j.lastRealRun.startedAt : null),
      render: (j) => (j.lastRealRun
        ? el('span', {}, dateTimeIst(j.lastRealRun.startedAt), ' ', outcomeBadge(j.lastRealRun.outcome),
          sub(runDetail(j.lastRealRun)))
        : el('span.muted', {}, 'none yet')),
    },
    {
      key: 'took',
      label: 'Took',
      align: 'r',
      value: (j) => (j.lastRealRun ? j.lastRealRun.durationMs : null),
      render: (j) => (neverRan(j) ? el('span.faint', {}, 'no run yet')
        : el('span.num', {}, dur(j.lastRealRun ? j.lastRealRun.durationMs : null))),
    },
    {
      key: 'typical',
      label: 'Typical',
      align: 'r',
      value: (j) => j.stats.medianMs,
      render: (j) => (neverRan(j) ? QUIET() : el('span.num', {}, dur(j.stats.medianMs),
        j.stats.runsMeasured
          ? sub(`${j.stats.p90Ms ? `9 in 10 under ${dur(j.stats.p90Ms)} · ` : ''}max ${dur(j.stats.maxMs)} · ${j.stats.runsMeasured} run${j.stats.runsMeasured === 1 ? '' : 's'}`)
          : null)),
    },
    {
      key: 'delay',
      label: 'Start delay',
      value: (j) => (j.lastRealRun ? j.lastRealRun.startLagMs : null),
      render: (j) => (neverRan(j) ? QUIET() : el('span', {}, lag(j.lastRealRun ? j.lastRealRun.startLagMs : null),
        j.stats.maxLagMs && j.stats.maxLagMs >= 5000 ? sub(`worst ${dur(j.stats.maxLagMs)}`) : null)),
    },
    {
      key: 'window',
      label: `Last ${ov.windowDays} days`,
      value: (j) => j.counts.missed * 1000 + j.counts.failed + j.counts.withErrors + j.counts.interrupted,
      render: (j) => windowCell(j.counts),
    },
    {
      key: 'lastSucceededAt',
      label: 'Last clean success',
      render: (j) => (j.lastSucceededAt ? dateTimeIst(j.lastSucceededAt) : el('span.muted', {}, 'none on record')),
    },
  ], ov.jobs, { sortKey: 'verdict', sortDir: 'asc' });
}

/** "11 ran · 3 skipped · 1 missed", with the bad parts named first and coloured. */
function windowCell(c) {
  const bad = [];
  if (c.failed) bad.push(`${c.failed} failed`);
  if (c.withErrors) bad.push(`${c.withErrors} with errors`);
  if (c.interrupted) bad.push(`${c.interrupted} never finished`);
  if (c.missed) bad.push(`${c.missed} missed`);
  if (c.overlapped) bad.push(`${c.overlapped} swallowed`);
  const good = `${c.runs - c.skipped} ran · ${c.skipped} skipped`;
  return el('span', {},
    bad.length ? el('span.neg', {}, bad.join(' · ')) : null,
    bad.length ? el('br') : null,
    el('span', {}, good),
    c.appNotRunning ? sub(`${c.appNotRunning} fire${c.appNotRunning === 1 ? '' : 's'} while the app was off`) : null);
}

function problemsBlock(ov) {
  if (!ov.problems.length) {
    return empty('Nothing went wrong in this window',
      'No run failed, logged an error or was cut off, and no fire was lost while the app was up. '
      + '"Succeeded" still only means the job did not complain — Data Health checks whether what it wrote is fresh and measured.');
  }
  return table([
    { key: 'at', label: 'When', render: (p) => dateTimeIst(p.at) },
    { key: 'label', label: 'Job', render: (p) => el('span.cell-main', {}, p.label) },
    { key: 'kind', label: 'What happened', render: (p) => el('span', {}, outcomeBadge(p.kind), sub(p.headline)) },
    { key: 'detail', label: 'Detail', render: (p) => el('span', {}, p.detail || NOT_MEASURED) },
  ], ov.problems, { sortKey: 'at', sortDir: 'desc' });
}

// ------------------------------------------------------------------ timeline

const timelineHost = el('div');

async function loadTimeline() {
  const q = timelineDate ? `?date=${timelineDate}` : '';
  timelineHost.replaceChildren(el('div.muted', {}, 'Loading…'));
  try {
    const { data } = await get(`/api/jobs/timeline${q}`, { cache: false });
    timelineHost.replaceChildren(timelineBody(data));
  } catch (e) {
    timelineHost.replaceChildren(empty('Could not load the timeline', String(e && e.message ? e.message : e)));
  }
}

function dayButton(label, date) {
  return el('button.chip', {
    type: 'button',
    disabled: date ? null : 'disabled',
    onclick: () => { timelineDate = date; loadTimeline(); },
  }, label);
}

function timelineBody(tl) {
  const controls = el('div.form-row', {},
    dayButton('‹ Earlier day', tl.previousDate),
    el('strong', {}, tl.date ? shortDate(tl.date) : NOT_MEASURED),
    dayButton('Later day ›', tl.nextDate));

  const runs = tl.runs || [];
  if (!runs.length) {
    return el('div', {}, controls, empty('No runs on this day',
      'The app may not have been started, or this is before the ledger began.'));
  }

  // Axis: whole hours around the day's runs, never narrower than the market session.
  const starts = runs.map((r) => parseLocal(r.startedAt)).filter(Boolean);
  const ends = runs.map((r) => parseLocal(r.finishedAt) || parseLocal(r.startedAt)).filter(Boolean);
  const day = new Date(starts[0]); day.setHours(0, 0, 0, 0);
  const minH = Math.min(9, ...starts.map((d) => d.getHours()));
  const maxH = Math.max(16, ...ends.map((d) => d.getHours() + 1));
  const t0 = day.getTime() + minH * 3600000;
  const span = (maxH - minH) * 3600000;
  const pct = (d) => Math.max(0, Math.min(100, ((d.getTime() - t0) / span) * 100));

  // Real runs only for the concurrency count: a skip holds a thread for two milliseconds.
  const real = runs.filter((r) => r.outcome !== 'SKIPPED');
  let peak = 0;
  for (const r of real) {
    const a = parseLocal(r.startedAt);
    const n = real.filter((o) => {
      const s = parseLocal(o.startedAt);
      const e = parseLocal(o.finishedAt) || new Date(8.64e15);
      return s <= a && e > a;
    }).length;
    peak = Math.max(peak, n);
  }

  const byJob = new Map();
  for (const r of runs) {
    if (!byJob.has(r.jobKey)) byJob.set(r.jobKey, { label: r.label, runs: [] });
    byJob.get(r.jobKey).runs.push(r);
  }

  const ticks = el('div', { style: 'position:relative;height:16px;margin-left:230px;font-size:10.5px;color:var(--ink-muted)' });
  for (let h = minH; h <= maxH; h += 1) {
    ticks.append(el('span', {
      style: `position:absolute;left:${((h - minH) / (maxH - minH)) * 100}%;transform:translateX(-50%)`,
    }, `${String(h).padStart(2, '0')}:00`));
  }

  const rows = [...byJob.values()].map((j) => {
    const track = el('div', {
      style: 'position:relative;flex:1;height:14px;background:var(--surface-alt);border-radius:3px',
    });
    for (const r of j.runs) {
      const s = parseLocal(r.startedAt);
      const e = parseLocal(r.finishedAt) || (r.outcome === 'RUNNING' ? new Date() : s);
      const left = pct(s);
      const width = Math.max(0.25, pct(e) - left);
      const skipped = r.outcome === 'SKIPPED';
      track.append(el('span', {
        title: `${j.label} · ${hhmm(r.startedAt)}${r.finishedAt ? `–${hhmm(r.finishedAt)}` : ''} · `
          + `${dur(r.durationMs)} · ${(OUTCOME[r.outcome] || {}).label || r.outcome}`
          + (runDetail(r) ? ` · ${runDetail(r)}` : ''),
        style: `position:absolute;top:${skipped ? 5 : 1}px;height:${skipped ? 4 : 12}px;left:${left}%;`
          + `width:${width}%;min-width:3px;border-radius:2px;background:${BAR[r.outcome] || 'var(--ink-faint)'}`,
      }));
    }
    return el('div', { style: 'display:flex;align-items:center;gap:10px;margin:3px 0' },
      el('div', { style: 'width:220px;flex:none;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: j.label }, j.label),
      track);
  });

  const legend = el('div.muted', { style: 'font-size:12px;margin-top:10px;display:flex;gap:14px;flex-wrap:wrap' },
    ...[['SUCCEEDED', 'succeeded'], ['COMPLETED_WITH_ERRORS', 'errors / failed / never finished'],
      ['RUNNING', 'running'], ['SKIPPED', 'skipped (thin line)']].map(([k, t]) => el('span', {},
      el('span', { style: `display:inline-block;width:12px;height:8px;border-radius:2px;margin-right:5px;background:${BAR[k]}` }), t)));

  return el('div', {},
    controls,
    el('div', { style: 'font-size:13px;margin-bottom:10px' },
      `${real.length} real run${real.length === 1 ? '' : 's'} and ${runs.length - real.length} skip${runs.length - real.length === 1 ? '' : 's'}. `
      + `At most ${peak} job${peak === 1 ? '' : 's'} ran at once, on a pool of ${tl.poolSize} threads`
      + (peak >= tl.poolSize ? ' — every thread was busy, so anything due then waited.' : '.')),
    ticks,
    ...rows,
    legend);
}

// ------------------------------------------------------------------ one job's history

const historyHost = el('div');

async function loadHistory() {
  if (!historyJob) return;
  historyHost.replaceChildren(el('div.muted', {}, 'Loading…'));
  try {
    const { data } = await get(`/api/jobs/runs?job=${encodeURIComponent(historyJob)}&days=30`, { cache: false });
    historyHost.replaceChildren(historyBody(data));
  } catch (e) {
    historyHost.replaceChildren(empty('Could not load this job’s runs', String(e && e.message ? e.message : e)));
  }
}

function historyBody(h) {
  const st = h.stats;
  return el('div', {},
    el('div', { style: 'font-size:13px;margin-bottom:10px' },
      `${h.schedule || ''}. Over the last ${h.windowDays} days: typical ${dur(st.medianMs)}`
      + `${st.p90Ms ? `, 9 in 10 under ${dur(st.p90Ms)}` : ''}, slowest ${dur(st.maxMs)}, `
      + `across ${st.runsMeasured} real run${st.runsMeasured === 1 ? '' : 's'}.`),
    table([
      { key: 'startedAt', label: 'Started', render: (r) => dateTimeIst(r.startedAt) },
      { key: 'scheduledFor', label: 'Due at', render: (r) => (r.scheduledFor ? hhmm(r.scheduledFor) : NOT_MEASURED) },
      { key: 'startLagMs', label: 'Delay', render: (r) => lag(r.startLagMs) },
      { key: 'durationMs', label: 'Took', align: 'r', render: (r) => el('span.num', {}, dur(r.durationMs)) },
      { key: 'outcome', label: 'Result', render: (r) => outcomeBadge(r.outcome) },
      {
        key: 'errorsLogged',
        label: 'Errors · warnings',
        align: 'r',
        render: (r) => el('span.num', {}, `${r.errorsLogged ?? '–'} · ${r.warningsLogged ?? '–'}`),
      },
      { key: 'detail', label: 'Detail', value: (r) => runDetail(r), render: (r) => el('span', {}, runDetail(r) || '') },
      { key: 'threadName', label: 'Thread' },
    ], h.runs, { sortKey: 'startedAt', sortDir: 'desc', emptyMessage: 'No runs recorded in this window.' }));
}

function historyPicker(ov) {
  const select = el('select.field', {
    onchange: (e) => { historyJob = e.target.value; loadHistory(); },
  }, ...ov.jobs
    .slice()
    .sort((a, b) => a.label.localeCompare(b.label))
    .map((j) => el('option', { value: j.key, selected: j.key === historyJob ? 'selected' : null }, j.label)));
  return el('div.form-row', {}, el('span', {}, 'Job'), select);
}

// ------------------------------------------------------------------ reading guide

function readingGuide() {
  const rows = [
    ['SUCCEEDED', 'Did not throw and logged no ERROR line. It does not mean the job produced anything.'],
    ['COMPLETED_WITH_ERRORS', 'Returned normally but logged at least one ERROR — usually a failure the job caught itself. The first error is shown beside it. This is the badge to look for: "did it throw?" would call nearly all of these a success.'],
    ['FAILED', 'Threw out of the job.'],
    ['INTERRUPTED', 'Was still running when the app stopped — the session-end shutdown, a restart or a crash. Its work may be half-written.'],
    ['SKIPPED', 'Returned at a guard: outside 09:15–15:30, a config switch off (named), or a weekend. Expected for fires outside the window; worth a look when the reason names a switch.'],
    ['MISSED', 'Due while the app was demonstrably running, and nothing ran. The one unexplained case.'],
    ['OVERLAPPED', 'Due while this job\'s previous run was still going. Spring times the next fire from when a run finishes, so a run that outlasts its interval swallows the fires it overlaps.'],
  ];
  return card(el('div', { style: 'display:flex;flex-direction:column;gap:8px' },
    ...rows.map(([k, t]) => el('div', { style: 'display:flex;gap:10px;align-items:baseline' },
      el('span', { style: 'flex:none;width:170px' }, outcomeBadge(k)),
      el('span', { style: 'font-size:13px;line-height:1.55' }, t))),
    el('div', { style: 'font-size:13px;line-height:1.55;margin-top:6px' },
      'Fires that fell while the app was switched off are counted, not judged, and drawn as "while the app was off". '
      + '"Start delay" is how long after its scheduled time a run began; more than a couple of minutes means all four '
      + 'scheduler threads were busy (B-014), which is how the afternoon jobs used to miss the 15:30 cut-off.')));
}

function notRecordedCard(items) {
  return card(el('ul', { style: 'margin:0;padding-left:20px;font-size:13px;line-height:1.7' },
    ...items.map((t) => el('li', {}, t))));
}

// ------------------------------------------------------------------ page

function paint(ov) {
  const s = ov.summary;
  const headline = s.problem ? `${s.problem} need attention` : s.watch ? `${s.watch} to watch` : 'All clear';

  if (!historyJob || !ov.jobs.some((j) => j.key === historyJob)) {
    const first = ov.jobs.find((j) => j.verdict === 'PROBLEM') || ov.jobs[0];
    historyJob = first ? first.key : null;
  }

  mount(view,
    withSummary(section('Where the jobs stand',
      `Every scheduled job, from the scheduler itself, with the last ${ov.windowDays} days of runs. `
      + 'A job is a Problem when its latest real run failed, logged an error or never finished, or when a fire was lost '
      + 'while the app was up. Read as of ' + dateTimeIst(ov.generatedAt) + '.',
      summaryRow(ov)),
    headline, { type: s.problem ? 'danger' : s.watch ? 'warning' : 'success' }),

    withCount(section('Running now',
      'Jobs in progress in this session. A run longer than nine in ten of its own past runs is flagged.',
      ov.runningNow.length ? runningTable(ov) : empty('Nothing is running', 'Every scheduler thread is idle.')),
    ov.runningNow.length),

    withCount(section('Every scheduled job',
      'Sorted with the ones that need attention first. "Last fired" includes skips; "Last real run" is the newest run '
      + 'that actually did its work, which is what Took and Start delay describe. Typical is the middle run, not the '
      + 'average, so one slow day does not move it.',
      jobsTable(ov)), ov.jobs.length),

    withCount(section('What went wrong',
      `Every failed, errored or cut-off run and every lost fire in the last ${ov.windowDays} days, newest first.`,
      problemsBlock(ov)), ov.problems.length),

    section('Day timeline',
      'Each bar is one run, placed at the time it ran and as long as it took. Overlapping bars ran at the same time; '
      + 'when as many overlap as there are scheduler threads, anything else due had to wait. Hover a bar for its details.',
      card(timelineHost)),

    section('One job\'s runs',
      'Every run of one job over the last 30 days, with when it was due, how late it started and what it logged.',
      card(historyPicker(ov), historyHost)),

    section('How to read this page',
      'What each result means, and why "did not throw" is not the same as "worked".',
      readingGuide()),

    withCount(section('What this page cannot see',
      'The honest limits of the run ledger.',
      notRecordedCard(ov.notRecorded || [])), (ov.notRecorded || []).length));

  loadTimeline();
  loadHistory();
}

async function render() {
  mount(view, section('Jobs', 'Loading…', card(el('div.muted', {}, 'Loading…'))));
  const { data } = await get(`/api/jobs?days=${WINDOW_DAYS}`, { cache: false });
  overview = data;
  paint(overview);
}

async function reload() {
  await initChrome();
  await render();
}

reload().then(() => registerRefresh(reload)).catch((e) => {
  mount(view, section('Jobs', 'Could not read the job ledger',
    card(el('div', {}, 'The app did not answer, or the ledger could not be read. Start it with start-app.bat and reload.'),
      el('div.muted', { style: 'margin-top:6px;font-size:12px' }, String(e && e.message ? e.message : e)))));
});
