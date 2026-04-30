package com.haizz.exchange.marketdata.domain.exception;

import com.haizz.exchange.common.web.ServiceUnavailableException;

public class DepthUnavailableException extends ServiceUnavailableException {

    public DepthUnavailableException(String pair) {
        super("DEPTH_UNAVAILABLE", "Depth data unavailable for pair: " + pair);
    }
}
