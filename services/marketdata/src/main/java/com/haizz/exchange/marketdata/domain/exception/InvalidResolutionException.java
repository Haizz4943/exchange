package com.haizz.exchange.marketdata.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class InvalidResolutionException extends ValidationException {

    public InvalidResolutionException(String resolution) {
        super("INVALID_RESOLUTION", "Unsupported resolution: " + resolution + ". Supported: 1, 5, 15, 60, 240, 1D");
    }
}
