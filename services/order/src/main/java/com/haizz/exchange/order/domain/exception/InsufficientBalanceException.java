package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class InsufficientBalanceException extends ValidationException {
    public InsufficientBalanceException(String message) {
        super("INSUFFICIENT_BALANCE", message);
    }
}
