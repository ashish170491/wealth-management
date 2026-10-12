package com.example.trading.funds;

/**
 * Open / close / interval, read from the text before the category parenthesis in AMFI's
 * block headers.
 *
 * <p>Load-bearing for the universe filter: 4,711 of the 14,375 rows in the live file are
 * {@link #CLOSE_ENDED} - fixed-maturity plans that closed years ago and still list a NAV.
 * Screening them would trebles the universe with schemes nobody can buy.
 */
public enum SchemeStructure {
    OPEN_ENDED,
    CLOSE_ENDED,
    INTERVAL,
    UNKNOWN
}
