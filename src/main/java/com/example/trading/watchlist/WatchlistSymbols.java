package com.example.trading.watchlist;

/** Symbol normalisation for watchlist input. Pure; pinned by {@code WatchlistSymbolsTest}. */
public final class WatchlistSymbols {

    private WatchlistSymbols() {}

    /**
     * {@code reliance} → {@code NSE:RELIANCE}; {@code nse:kwil-be} → {@code NSE:KWIL}.
     * The NSE trading-series suffix is stripped because Kite tradingsymbols never carry it (B-013).
     *
     * @throws IllegalArgumentException for blank input or an unknown exchange prefix
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        String s = raw.trim().toUpperCase().replaceAll("\\s+", "");
        String exchange = "NSE";
        String trading = s;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            exchange = s.substring(0, colon);
            trading = s.substring(colon + 1);
        }
        if (!exchange.equals("NSE") && !exchange.equals("BSE")) {
            throw new IllegalArgumentException("Unknown exchange '" + exchange + "' — use NSE:SYMBOL or BSE:SYMBOL");
        }
        trading = trading.replaceAll("-(BE|BZ|BL|IL)$", "");
        if (trading.isEmpty() || !trading.matches("[A-Z0-9&\\-]+")) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid trading symbol");
        }
        return exchange + ":" + trading;
    }

    /** {@code NSE:RELIANCE} → {@code RELIANCE}. */
    public static String tradingSymbol(String symbol) {
        if (symbol == null) return null;
        int colon = symbol.indexOf(':');
        return colon >= 0 ? symbol.substring(colon + 1) : symbol;
    }
}
