/**
 * Read-only JSON feed for the local dashboard UI (SPEC section 27).
 *
 * <h2>The contract</h2>
 * <p><b>This package contains no analysis.</b> It fans out to module services, merges their
 * output and shapes it for the wire. If you are about to write arithmetic on a domain value
 * here, the computation belongs in the owning module instead - put it next to the code that
 * already produces the same number for the email reports, so both surfaces agree.
 *
 * <p><b>Read-only, enforced in review by SPEC section 20 rule 7.</b> No endpoint in this
 * package may write to the database, call the broker, call an external API, or send email.
 * Anything expensive or side-effecting is exposed by the UI as an explicit button against
 * its own module's endpoint, never as a page-load fetch.
 *
 * <h2>The dependency arrow</h2>
 * <p>Nothing in the codebase depends on this package; this package depends on many. That
 * one-way arrow is the design - it is what lets the dashboard compose across modules
 * without any module having to know a dashboard exists. Do not import from here into a
 * module package.
 *
 * <h2>Why the UI is not the source of truth</h2>
 * <p>The email reports and this feed must never compute the same number two different ways.
 * Where a value already exists as a service return type, serialize it; where it only exists
 * inside an HTML string builder, the fix is to extract it in that module, not to recompute
 * it here. Report bodies that are still welded to HTML are served as HTML instead
 * (SPEC section 27.6) precisely to avoid a second, drifting implementation.
 */
package com.example.trading.dashboard;
