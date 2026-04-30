package com.haizz.exchange.marketdata.domain;

import java.util.Objects;

public record PairSymbol(String value) {

    public PairSymbol {
        Objects.requireNonNull(value, "pair symbol must not be null");
        value = value.toUpperCase().strip();
        if (value.isBlank()) throw new IllegalArgumentException("pair symbol must not be blank");
    }

    public static PairSymbol of(String value) {
        return new PairSymbol(value);
    }

    @Override
    public String toString() {
        return value;
    }

    public String toLowerCaseStream(String suffix) {
        return value.toLowerCase() + suffix;
    }
}
