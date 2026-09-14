<!--
Sync Impact Report
- Version change: N/A -> 1.0.0
- Modified principles:
	- Template Principle 1 -> I. Investment Decision Support First
	- Template Principle 2 -> II. Tracking-Only Safety Mode
	- Template Principle 3 -> III. Data Integrity and Traceability
	- Template Principle 4 -> IV. Explainable Signals and Recommendations
	- Template Principle 5 -> V. Pragmatic Validation, No Mandatory Unit-Test Gate
- Added sections:
	- Operating Constraints
	- Workflow and Quality Gates
- Removed sections:
	- None
- Templates requiring updates:
	- .specify/templates/plan-template.md ✅ updated
	- .specify/templates/spec-template.md ✅ reviewed (no change required)
	- .specify/templates/tasks-template.md ✅ reviewed (already aligned)
	- .specify/templates/commands/*.md ⚠ pending (directory not present)
- Runtime guidance updates:
	- QUICK_START.md ✅ updated with tracking-only operating note
- Follow-up TODOs:
	- None
-->

# Intraday Investment Intelligence Constitution

## Core Principles

### I. Investment Decision Support First
All product changes MUST improve decision quality for investment tracking, portfolio
review, multibagger discovery, trend detection, or market/news interpretation. Features
that primarily optimize automated trade execution MUST be treated as out of scope unless
the operating mode is explicitly re-enabled by the owner. Rationale: the current product
mission is informed investing, not autonomous order placement.

### II. Tracking-Only Safety Mode
System behavior MUST default to non-executing operation: order placement pathways MUST
remain disabled in normal operation, and any code path that can place live orders MUST be
guarded by explicit configuration and documented operator intent. Analytics, scanners,
watchlists, holdings review, and reports MUST continue to function when trading is off.
Rationale: safety and trust require strict separation between intelligence and execution.

### III. Data Integrity and Traceability
Every user-visible insight MUST be traceable to concrete, timestamped market, holdings,
or news data. Services producing scores, rankings, or recommendations MUST log source
inputs and key calculation factors. When data is stale, missing, or conflicting, the
system MUST degrade gracefully and clearly communicate uncertainty. Rationale: investment
decisions depend on reliable provenance, not opaque output.

### IV. Explainable Signals and Recommendations
All strategy signals, watchlist rankings, multibagger scores, and report conclusions MUST
include concise rationale that a human can review. Black-box outputs without reason codes,
weights, or factor summaries are non-compliant. AI-generated narrative MUST be additive
only and MUST never replace deterministic metrics. Rationale: explainability is required
for confidence and responsible decision-making.

### V. Pragmatic Validation, No Mandatory Unit-Test Gate
Unit tests are OPTIONAL unless explicitly requested by the feature specification or owner.
Validation MUST focus on functional evidence appropriate to this domain: backtests,
historical replays, signal audits, report correctness checks, and manual endpoint
verification. Any change that affects calculations, ranking logic, or risk filters MUST
ship with documented validation steps and outcomes. Rationale: domain confidence comes
from market-behavior validation, not unit-test quotas.

## Operating Constraints

- Primary stack MUST remain Spring Boot on Java 21 unless an approved amendment changes
	platform policy.
- Time-sensitive logic MUST use Asia/Kolkata timezone and market-hour guards.
- External market and news integrations MUST apply retry limits, timeout bounds, and
	stale-data handling so intelligence features remain available under partial outages.
- Configuration for broker credentials, API keys, and secrets MUST remain externalized
	through environment variables or protected configuration.
- Production-facing outputs (emails, dashboards, APIs) MUST prefer clarity over volume:
	prioritize actionable summaries with links to supporting metrics.

## Workflow and Quality Gates

1. Every feature spec MUST state investment user value and identify whether trading
	execution paths are touched.
2. Plans MUST include a Constitution Check confirming tracking-only safety,
	explainability, and validation evidence.
3. Tasks MUST include validation activities for any changed scoring, filtering, or
	recommendation behavior.
4. Pull requests MUST include: scope summary, impacted data sources, validation evidence,
	 and rollback strategy for data-quality regressions.
5. Releases SHOULD prefer incremental, reversible changes to analytics/reporting logic.

## Governance

This constitution is the authoritative governance document for product direction and
delivery standards in this repository.

- Amendment process: propose changes in writing, include rationale and impacted templates,
	and obtain explicit owner approval before merge.
- Versioning policy (semantic):
	- MAJOR for incompatible governance changes or principle removals/redefinitions.
	- MINOR for new principles/sections or materially expanded obligations.
	- PATCH for clarifications, wording improvements, or typo-only refinements.
- Compliance review expectations:
	- During planning: Constitution Check MUST pass before implementation starts.
	- During review: reviewers MUST verify evidence for safety mode, explainability,
		and validation commitments.
	- During release: unresolved constitution violations MUST be documented and explicitly
		accepted by the owner.

Operational guidance remains in CLAUDE.md and project docs, but no runtime guidance may
override this constitution.

**Version**: 1.0.0 | **Ratified**: 2026-04-18 | **Last Amended**: 2026-04-18
