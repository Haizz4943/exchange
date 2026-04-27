package com.haizz.exchange.wallet.domain.exception;

public class DepositAssetNotSupportedException extends WalletException {
    public DepositAssetNotSupportedException() {
        super("DEPOSIT_ASSET_NOT_SUPPORTED",
                "Only USDT deposits are supported in this release.");
    }
}
