package com.haizz.exchange.wallet.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record WalletsResponse(
        List<WalletDto> wallets,
        BigDecimal totalValueInUSDT  // null when Market Data Service unavailable
) {}
