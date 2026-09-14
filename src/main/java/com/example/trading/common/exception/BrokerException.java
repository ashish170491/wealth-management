package com.example.trading.common.exception;

public class BrokerException extends TradingException {
    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
