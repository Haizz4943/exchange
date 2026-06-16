package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class MaxOpenOrdersExceededException extends ValidationException {
    public MaxOpenOrdersExceededException(String pair, int max) {
        super("MAX_OPEN_ORDERS_EXCEEDED",
                "Maximum of " + max + " open orders reached for pair " + pair);
    }
}
