package com.haizz.exchange.marketdata.shared;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StartupState {

    private final AtomicBoolean exchangeInfoLoaded = new AtomicBoolean(false);
    private final AtomicBoolean backfillComplete = new AtomicBoolean(false);
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);

    public boolean isExchangeInfoLoaded() { return exchangeInfoLoaded.get(); }
    public boolean isBackfillComplete() { return backfillComplete.get(); }
    public boolean isWsConnected() { return wsConnected.get(); }

    public void markExchangeInfoLoaded() { exchangeInfoLoaded.set(true); }
    public void markBackfillComplete() { backfillComplete.set(true); }
    public void markWsConnected() { wsConnected.set(true); }
    public void markWsDisconnected() { wsConnected.set(false); }

    public boolean isReady() {
        return exchangeInfoLoaded.get() && backfillComplete.get() && wsConnected.get();
    }
}
