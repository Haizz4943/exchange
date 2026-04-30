package com.haizz.exchange.marketdata.domain.exception;

import com.haizz.exchange.common.web.ServiceUnavailableException;

public class TickerUnavailableException extends ServiceUnavailableException {

    public TickerUnavailableException(String pair) {
        super("TICKER_UNAVAILABLE", "Ticker data unavailable for pair: " + pair);
    }
}
