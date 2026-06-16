package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ConflictException;

public class OrderNotCancellableException extends ConflictException {
    public OrderNotCancellableException(String orderId, String state) {
        super("ORDER_NOT_CANCELLABLE",
                "Order " + orderId + " cannot be cancelled in state " + state);
    }
}
