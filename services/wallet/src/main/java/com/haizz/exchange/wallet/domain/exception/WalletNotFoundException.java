package com.haizz.exchange.wallet.domain.exception;

public class WalletNotFoundException extends WalletException {
    public WalletNotFoundException(String userId, String assetCode) {
        super("WALLET_NOT_FOUND",
                "Wallet not found for userId=" + userId + " assetCode=" + assetCode);
    }
}
