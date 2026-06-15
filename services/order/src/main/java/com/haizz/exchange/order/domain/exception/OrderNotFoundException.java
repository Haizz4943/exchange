package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.NotFoundException;

public class OrderNotFoundException extends NotFoundException {
    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", "Order not found: " + orderId);
    }
}
