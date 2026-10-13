package com.example.trading.broker.kite;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a Kite access token stops working (B-171). Pure: no clock, no I/O.
 *
 * <p>Kite expires every access token at about 06:00 IST the morning after it was issued,
 * whatever time it was issued. A token issued at 22:53 is therefore dead at 06:00, eight hours
 * later, and one issued at 09:01 lives until 06:00 the next day. This class answers the only
 * question that matters for that: was the token issued before the most recent 06:00?
 *
 * <p>An unknown issue time is not "expired". A token supplied through configuration has no
 * recorded issue time, and calling it dead would refuse every call on a session that may well be
 * live. Kite rejecting it is still caught, by the 403 path in {@code KiteBrokerClient}.
 */
public final class KiteSessionClock {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Kite's daily session cut-off. */
    public static final LocalTime DAILY_EXPIRY = LocalTime.of(6, 0);

    private KiteSessionClock() {
    }

    /** The most recent 06:00 IST at or before {@code now}. */
    public static Instant lastExpiry(Instant now) {
        ZonedDateTime local = now.atZone(IST);
        ZonedDateTime todayCut = local.toLocalDate().atTime(DAILY_EXPIRY).atZone(IST);
        return (local.isBefore(todayCut) ? todayCut.minusDays(1) : todayCut).toInstant();
    }

    /**
     * True only when the issue time is known and falls before the most recent 06:00. Null means
     * "not recorded", which this deliberately does not treat as expired.
     */
    public static boolean isExpired(Instant issuedAt, Instant now) {
        return issuedAt != null && issuedAt.isBefore(lastExpiry(now));
    }
}
