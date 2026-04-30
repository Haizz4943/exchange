package com.haizz.exchange.common.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Price(BigDecimal value) implements Comparable<Price> {

    public Price {
        Objects.requireNonNull(value, "price value must not be null");
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive, got: " + value);
        }
    }

    public static Price of(BigDecimal value) {
        return new Price(value.setScale(18, RoundingMode.HALF_UP));
    }

    public static Price of(String value) {
        return of(new BigDecimal(value));
    }

    public boolean isPositive() {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }

    @Override
    public int compareTo(Price other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
