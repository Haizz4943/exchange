package com.haizz.exchange.common.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    public Quantity {
        Objects.requireNonNull(value, "quantity value must not be null");
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative, got: " + value);
        }
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value.setScale(18, RoundingMode.HALF_UP));
    }

    public static Quantity of(String value) {
        return of(new BigDecimal(value));
    }

    public static Quantity zero() {
        return new Quantity(BigDecimal.ZERO.setScale(18, RoundingMode.HALF_UP));
    }

    public boolean isPositive() {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    public Quantity subtract(Quantity other) {
        BigDecimal result = this.value.subtract(other.value);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Subtraction would result in negative quantity");
        }
        return new Quantity(result);
    }

    @Override
    public int compareTo(Quantity other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
