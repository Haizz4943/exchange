package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ServiceUnavailableException;

public class MarketDataUnavailableException extends ServiceUnavailableException {
    public MarketDataUnavailableException(String message) {
        super("MARKET_DATA_UNAVAILABLE", message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super("MARKET_DATA_UNAVAILABLE", message + ": " + cause.getMessage());
    }
}
