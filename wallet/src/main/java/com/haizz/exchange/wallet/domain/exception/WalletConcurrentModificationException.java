package com.haizz.exchange.wallet.domain.exception;

public class WalletConcurrentModificationException extends WalletException {
    public WalletConcurrentModificationException() {
        super("CONCURRENT_MODIFICATION",
                "Wallet was modified concurrently. Please retry.");
    }
}
