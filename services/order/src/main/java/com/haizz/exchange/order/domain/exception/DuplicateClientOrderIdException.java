package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ConflictException;

public class DuplicateClientOrderIdException extends ConflictException {
    public DuplicateClientOrderIdException(String clientOrderId) {
        super("DUPLICATE_CLIENT_ORDER_ID",
                "An order with clientOrderId " + clientOrderId + " already exists");
    }
}
