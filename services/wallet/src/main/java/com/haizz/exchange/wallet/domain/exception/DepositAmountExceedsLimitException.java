package com.haizz.exchange.wallet.domain.exception;

import java.math.BigDecimal;

public class DepositAmountExceedsLimitException extends WalletException {
    public DepositAmountExceedsLimitException(BigDecimal limit) {
        super("DEPOSIT_AMOUNT_EXCEEDS_LIMIT",
                "Maximum deposit per transaction is " + limit.toPlainString() + " USDT.");
    }
}
