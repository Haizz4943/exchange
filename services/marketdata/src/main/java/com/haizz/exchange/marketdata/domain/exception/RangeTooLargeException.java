package com.haizz.exchange.marketdata.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class RangeTooLargeException extends ValidationException {

    public RangeTooLargeException(int requested, int max) {
        super("RANGE_TOO_LARGE", "Requested " + requested + " bars exceeds maximum " + max);
    }
}
