package com.haizz.exchange.marketdata.domain.exception;

import com.haizz.exchange.common.web.NotFoundException;

public class PairNotSupportedException extends NotFoundException {

    public PairNotSupportedException(String pair) {
        super("PAIR_NOT_SUPPORTED", "Pair not supported: " + pair);
    }
}
