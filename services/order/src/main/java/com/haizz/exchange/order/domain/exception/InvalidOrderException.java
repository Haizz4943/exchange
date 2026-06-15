package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ValidationException;

public class InvalidOrderException extends ValidationException {
    public InvalidOrderException(String message) {
        super("INVALID_ORDER", message);
    }

    /**
     * Allows a specific API_SPEC error code (e.g. INVALID_QUANTITY, INVALID_PRICE,
     * INVALID_SIDE, INVALID_ORDER_TYPE, LIMIT_PRICE_REQUIRED, LIMIT_PRICE_NOT_ALLOWED,
     * BELOW_MIN_NOTIONAL) to be carried in the error response.
     */
    public InvalidOrderException(String code, String message) {
        super(code, message);
    }
}
