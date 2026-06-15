package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class InvalidOrderException extends ValidationException {
    public InvalidOrderException(String message) {
        super("INVALID_ORDER", message);
    }
}
