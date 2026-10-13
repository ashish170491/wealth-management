package com.example.trading.broker.kite;

/**
 * Published by {@link KiteBrokerClient} when the session it is using is dead: Kite answered with
 * a {@code TokenException}, or the token was issued before the last 06:00 cut-off (B-171).
 * {@link TokenManagementService} listens and logs in again.
 *
 * <p>An event rather than a direct call so the broker client, which every service depends on,
 * does not also depend on the login machinery.
 *
 * @param reason one line saying why, for the log
 */
public record KiteTokenRejectedEvent(String reason) {
}
