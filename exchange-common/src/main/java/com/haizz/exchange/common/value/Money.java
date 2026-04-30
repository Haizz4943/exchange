package com.haizz.exchange.common.value;

import com.haizz.exchange.common.enums.AssetCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, AssetCode asset) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative");
        }
    }

    public static Money of(BigDecimal amount, AssetCode asset) {
        return new Money(amount.setScale(18, RoundingMode.HALF_UP), asset);
    }

    public static Money zero(AssetCode asset) {
        return new Money(BigDecimal.ZERO.setScale(18, RoundingMode.HALF_UP), asset);
    }

    public Money add(Money other) {
        requireSameAsset(other);
        return new Money(this.amount.add(other.amount), this.asset);
    }

    public Money subtract(Money other) {
        requireSameAsset(other);
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Subtraction would result in negative money");
        }
        return new Money(result, this.asset);
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    private void requireSameAsset(Money other) {
        if (!this.asset.equals(other.asset)) {
            throw new IllegalArgumentException("Cannot operate on different assets: " + this.asset + " vs " + other.asset);
        }
    }
}
