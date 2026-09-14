package com.example.trading.common.logging;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class TradeContext {

    private static final String CORRELATION_ID = "correlationId";

    /**
     * Initializes a new correlation ID for a trade execution flow.
     */
    public void init(String symbol) {
        String id = UUID.randomUUID().toString().substring(0, 8) + "-" + symbol;
        MDC.put(CORRELATION_ID, id);
        log.info("Initialized Trade Context with Correlation ID: {}", id);
    }

    /**
     * Clears the correlation ID.
     */
    public void clear() {
        MDC.remove(CORRELATION_ID);
    }

    public String getCorrelationId() {
        return MDC.get(CORRELATION_ID);
    }
}
