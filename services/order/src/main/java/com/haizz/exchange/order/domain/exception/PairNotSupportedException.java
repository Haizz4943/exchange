package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class PairNotSupportedException extends ValidationException {
    public PairNotSupportedException(String pair) {
        super("PAIR_NOT_SUPPORTED", "Trading pair not supported: " + pair);
    }
}
