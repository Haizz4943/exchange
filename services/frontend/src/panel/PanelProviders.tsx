'use client';

/**
 * PanelProviders — wraps all instance-scoped contexts for one panel mount.
 * This is the single place where Zustand store instances are created.
 * Multiple embeds on the same page each get their own stores (no singleton leak).
 */

import React, { useState } from 'react';
import { TradeStoreContext, createTradeStore } from '@/features/trade/store';
import { OrdersStoreContext, createOrdersStore } from '@/features/orders/store';
import { WalletStoreContext, createWalletStore } from '@/features/wallet/store';
import { WsStoreSyncer } from './WsStoreSyncer';

export function PanelStoreProviders({ children }: { children: React.ReactNode }) {
  // Use useState so stores are created once per mount and never re-created on re-render
  const [tradeStore] = useState(() => createTradeStore());
  const [ordersStore] = useState(() => createOrdersStore());
  const [walletStore] = useState(() => createWalletStore());

  return (
    <TradeStoreContext.Provider value={tradeStore}>
      <OrdersStoreContext.Provider value={ordersStore}>
        <WalletStoreContext.Provider value={walletStore}>
          <WsStoreSyncer />
          {children}
        </WalletStoreContext.Provider>
      </OrdersStoreContext.Provider>
    </TradeStoreContext.Provider>
  );
}
