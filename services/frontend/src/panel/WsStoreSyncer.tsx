'use client';

/**
 * WsStoreSyncer — mounts near the panel root; listens to all incoming WS messages
 * and dispatches to the appropriate Zustand store.
 * Placed in panel/ (not lib/ws/) because it imports from features/ which lib/ cannot.
 * See §5.5 of SystemDesign_Appendix_Frontend.md.
 */

import { useEffect } from 'react';
import { useWsClient } from '@/lib/ws/WsProvider';
import { useTradeStoreRaw } from '@/features/trade/store';
import { useOrdersStoreRaw, FillUpdate } from '@/features/orders/store';
import { useWalletStoreRaw, BalanceChangeEvent } from '@/features/wallet/store';

export function WsStoreSyncer() {
  const ws = useWsClient();
  const tradeStore = useTradeStoreRaw();
  const ordersStore = useOrdersStoreRaw();
  const walletStore = useWalletStoreRaw();

  useEffect(() => {
    const unsubs = [
      ws.onSchema('market-data.depth.v1', (payload) => {
        const msg = payload as { pair: string; bids: [string, string][]; asks: [string, string][] };
        tradeStore.getState().applyDepthUpdate(msg.pair, { bids: msg.bids, asks: msg.asks });
      }),

      ws.onSchema('market-data.events.v1.ExternalTradeObserved', (payload) => {
        const msg = payload as {
          pair: string;
          price: string;
          quantity: string;
          executedAt: string;
        };
        tradeStore.getState().appendRecentTrade(msg.pair, msg);
      }),

      ws.onSchema('matching.events.v1.OrderPartiallyFilled', (payload) => {
        ordersStore.getState().applyFillUpdate(payload as FillUpdate);
      }),

      ws.onSchema('matching.events.v1.OrderFilled', (payload) => {
        ordersStore.getState().applyFillUpdate(payload as FillUpdate);
      }),

      ws.onSchema('matching.events.v1.OrderCancelled', (payload) => {
        ordersStore.getState().applyFillUpdate(payload as FillUpdate);
      }),

      ws.onSchema('wallet.events.v1.WalletTransactionRecorded', (payload) => {
        walletStore.getState().applyBalanceChange(payload as BalanceChangeEvent);
      }),
    ];

    return () => unsubs.forEach((u) => u());
  }, [ws, tradeStore, ordersStore, walletStore]);

  return null;
}
