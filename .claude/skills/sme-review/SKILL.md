---
name: sme-review
description: Run the stock-market SME reviewer over a feature, a tier, or the whole app — does what each feature tells the investor match what its data can actually support? Use when the user asks to review/audit/verify a feature, a lens, a scoring dimension, a report section or a dashboard screen, or asks what to improve next. Read-only; produces findings, draft BUGS.md entries and gated suggestions.
---

# SME review

Drives the `sme-agent` agent (`.claude/agents/sme-agent.md`). Read-only throughout —
nothing here edits code, SPEC.md, BUGS.md or CLAUDE.md.

## 1. Pick a scope — "all features" is not one pass

There are ~40 SPEC sections live. A single agent asked to verify all of them produces a shallow
skim, which is worse than nothing because it reads like coverage. Scope from
`reference/feature-inventory.md`:

| Ask | Scope |
|---|---|
| "review X" | One feature. One agent. Deepest, most useful mode |
| "review the scoring" | Tier 1 — the seven dimensions, five bonuses, the cap, the candidate gate |
| "review the lenses" | Tier 2 — the shadow signals and verdict lenses |
| "is the app honest" | Tier 3 first — the instruments. If they are broken, every other answer is unreliable |
| "review everything" | Tier 1 → 2 → 3 across separate agent runs, ledger below. Say up front it is several passes |

Run independent scopes as **parallel agents in one message**; they share no state. Do not fan out
more than ~4 at once — several of them will want the same DB and the same running app.

## 2. Establish ground truth first, once

Do this in the parent before spawning, and pass the result into each agent's prompt so four agents
do not each re-derive it:

```bash
curl -s -o /dev/null -w "app=%{http_code}\n" -m 8 http://localhost:8080/api/dashboard/health
curl -s http://localhost:8080/api/accuracy/coverage | head -c 3000      # if app is up
git log --oneline -15
```

If the app is down (normal outside 09:00–15:45 IST weekdays), say so in the prompt and tell the
agent to work from code, SPEC, BUGS and direct SQL. Do **not** start the app for a review — that is
`/deploy-verify`, and it is the user's call.

## 3. Spawn

```
Agent(subagent_type: "sme-agent", prompt: "
  Scope: <feature name> (SPEC §NN).
  Ground truth: app is <up|down>; latest screening date <date>; coverage snapshot attached below.
  Follow your seven-step method. Report findings ranked by blast radius, draft BUGS.md entries for
  anything not already on file, and gated suggestions.
  <paste coverage/health output>
")
```

For a tier sweep, one agent per feature is better than one agent per tier — a feature is a unit of
evidence, a tier is a unit of scheduling.

## 4. Relay, don't dump

The agent's report does not reach the user. Relay:

1. The **verdict per feature** line.
2. Findings that would change what the investor does, with the direction and size of the error.
3. Suggestions that **passed** the gates, and the notable ones that failed with the gate they failed.
4. What could not be checked.

Drop anything the agent found that BUGS.md already records — say "already on file as B-NNN" once and
move on.

## 5. Ledger for a multi-pass sweep

Write to the scratchpad, not the repo:

```
%SCRATCH%/sme-sweep.md      # one line per feature: scope | verdict | findings | date
```

Resume from it rather than re-reviewing. A feature reviewed this week with no code change since does
not need a second pass.

## 6. After the review

Findings are **drafts**. The user decides what becomes a BUGS.md entry and what gets fixed. When they
say fix it, the normal repo rules apply: BUGS.md entry first with a blast-radius line, then the fix,
then an **empirical** verification — re-run the affected pipeline and observe the symptom gone, never
compile success alone (CLAUDE.md "Working with Bugs").

If a suggestion is material — a new scoring dimension, threshold, report, schedule or data source —
SPEC.md is updated in the same commit (§20), and the feature is classified in §40 **before** it is
designed.

## Guardrails

- The agent never edits and never calls a `POST`. If a review "needs" a write to proceed, that is a
  finding about observability, not a reason to write.
- Never conclude from a 30- or 90-day IC panel. SPEC §19 bars sub-180-day acceptance, and §40.2 says
  why: those panels are an early read over ~5 independent periods.
- Never propose re-weighting on the current IC (Gotcha 27). The route is §38.9 → §38.10, and nothing
  adopts automatically.
