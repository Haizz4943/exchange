package com.haizz.exchange.common.value;

import java.util.Objects;

public record Pair(String symbol) {

    public Pair {
        Objects.requireNonNull(symbol, "pair symbol must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("pair symbol must not be blank");
        }
        symbol = symbol.toUpperCase().strip();
    }

    public static Pair of(String symbol) {
        return new Pair(symbol);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
