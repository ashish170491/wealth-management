package com.example.trading.broker;

import java.util.Map;

/**
 * Common interface for all broker implementations.
 */
public interface BrokerClient {

    /**
     * Places a new order.
     * 
     * @param orderParams Parameters for the order.
     * @return Order ID or response details.
     */
    String placeOrder(Map<String, Object> orderParams);

    /**
     * Fetches current market quotes for a symbol.
     * 
     * @param symbol Trading symbol.
     * @return Quote details.
     */
    Map<String, Object> getQuote(String symbol);

    /**
     * Fetches historical candle data.
     * 
     * @param symbol   Trading symbol.
     * @param interval Timeframe (e.g., minute, day).
     * @param from     Start date.
     * @param to       End date.
     * @return List of historical data points.
     */
    java.util.List<Map<String, Object>> getHistoricalData(String symbol, String interval, String from, String to);

    /**
     * Authenticates with the broker.
     */
    void authenticate();

    /**
     * Fetches account margins and available funds.
     *
     * @return Map containing margin details including available balance, used margin, etc.
     */
    Map<String, Object> getAccountMargins();

    /**
     * Fetches all open positions from the broker.
     *
     * @return List of open positions with details like symbol, quantity, average price, P&L, etc.
     */
    java.util.List<Map<String, Object>> getPositions();

    /**
     * Exits/closes an existing position using the broker's position exit API.
     * This doesn't require additional margin as it closes an existing position.
     *
     * @param exchange Trading exchange (e.g., "NSE", "NFO")
     * @param tradingSymbol Trading symbol (e.g., "RELIANCE", "NIFTY24DECFUT")
     * @param product Product type (e.g., "MIS", "CNC")
     * @param quantity Quantity to exit
     * @param transactionType Exit transaction type ("BUY" to close short, "SELL" to close long)
     * @return Order ID of the exit order
     */
    String exitPosition(String exchange, String tradingSymbol, String product, int quantity, String transactionType);

    /**
     * Fetches all holdings (delivery positions) from the broker.
     * Holdings are long-term positions that are held in demat account.
     *
     * @return List of holdings with details like symbol, quantity, average price, current value, P&L, etc.
     */
    java.util.List<Map<String, Object>> getHoldings();

    /**
     * Fetches all orders for the current trading day.
     *
     * @return List of orders with details like order_id, status, symbol, quantity, price, etc.
     */
    java.util.List<Map<String, Object>> getOrders();

    /**
     * Fetches all executed trades for the current trading day. Each entry corresponds
     * to a single fill (one order can produce multiple trades when partially filled).
     * Used by the tax-lot auto-capture scheduler — Kite Connect does not expose
     * historical trades beyond today, so this needs to be polled while the market
     * is still open. SPEC.md §9.3.
     *
     * @return List of trades; each map includes {@code trade_id}, {@code order_id},
     *         {@code tradingsymbol}, {@code exchange}, {@code transaction_type}
     *         (BUY/SELL), {@code quantity}, {@code average_price}, {@code order_timestamp},
     *         {@code product}.
     */
    default java.util.List<Map<String, Object>> getTodayTrades() {
        return java.util.Collections.emptyList();
    }

    /**
     * Fetches the status and details of a specific order.
     *
     * @param orderId The broker order ID to check
     * @return Order details including status (OPEN, COMPLETE, CANCELLED, REJECTED), filled quantity, etc.
     */
    Map<String, Object> getOrderStatus(String orderId);

    /**
     * Cancels a pending/open order.
     *
     * @param orderId The broker order ID to cancel
     * @return true if cancellation was successful, false otherwise
     */
    boolean cancelOrder(String orderId);

    /**
     * Modifies an existing order (e.g., change price, quantity).
     *
     * @param orderId The broker order ID to modify
     * @param modifyParams Parameters to modify (price, quantity, trigger_price, etc.)
     * @return The order ID if modification was successful
     */
    String modifyOrder(String orderId, Map<String, Object> modifyParams);
}
