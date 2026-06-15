package com.haizz.exchange.order.domain;

public enum OrderState {
    NEW,
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_REQUESTED,
    CANCELLED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }

    public boolean isCancellable() {
        return this == NEW || this == OPEN || this == PARTIALLY_FILLED;
    }
}
