'use client';

/**
 * WsStoreSyncer — mounts near the panel root; listens to all incoming WS messages
 * and dispatches to the appropriate Zustand store / TanStack Query cache.
 * Placed in panel/ (not lib/ws/) because it imports from features/ which lib/ cannot.
 * See §5.5 of SystemDesign_Appendix_Frontend.md.
 */

import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useWsClient } from '@/lib/ws/WsProvider';
import { useWsSubscription } from '@/lib/ws/useWsSubscription';
import { useTradeStoreRaw } from '@/features/trade/store';
import { useOrdersStoreRaw, FillUpdate } from '@/features/orders/store';
import { useWalletStoreRaw } from '@/features/wallet/store';
import { useToast } from '@/components/ui/Toast';

export function WsStoreSyncer() {
  const ws = useWsClient();
  const queryClient = useQueryClient();
  const { push } = useToast();
  const tradeStore = useTradeStoreRaw();
  const ordersStore = useOrdersStoreRaw();
  const walletStore = useWalletStoreRaw();

  // The market channels are subscribed by OrderBook/TradesTape themselves;
  // orders/wallet have no dedicated component, so subscribe here globally.
  useWsSubscription('orders');
  useWsSubscription('wallet');

  useEffect(() => {
    const refreshOrders = () => {
      queryClient.invalidateQueries({ queryKey: ['orders'] });
      queryClient.invalidateQueries({ queryKey: ['trades'] });
    };

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

      // ── Order events (user-scoped `orders` channel, camelCase payloads) ──────
      ws.onSchema('matching.events.v1.OrderFilled', (payload) => {
        const p = payload as { orderId: string; filledQuantity?: string; avgPrice?: string };
        const mapped: FillUpdate = {
          order_id: p.orderId,
          state: 'FILLED',
          filled_qty: p.filledQuantity,
          avg_fill_price: p.avgPrice,
        };
        ordersStore.getState().applyFillUpdate(mapped);
        refreshOrders();
        push({ message: 'Lệnh đã khớp đầy đủ', variant: 'success' });
      }),

      ws.onSchema('matching.events.v1.OrderPartiallyFilled', (payload) => {
        const p = payload as { orderId: string; filledQuantity?: string; fillPrice?: string };
        const mapped: FillUpdate = {
          order_id: p.orderId,
          state: 'PARTIALLY_FILLED',
          filled_qty: p.filledQuantity,
          avg_fill_price: p.fillPrice,
        };
        ordersStore.getState().applyFillUpdate(mapped);
        refreshOrders();
        push({ message: 'Lệnh khớp một phần', variant: 'info' });
      }),

      ws.onSchema('matching.events.v1.OrderCancelled', (payload) => {
        const p = payload as { orderId: string; reason?: string };
        const mapped: FillUpdate = {
          order_id: p.orderId,
          state: 'CANCELLED',
        };
        ordersStore.getState().applyFillUpdate(mapped);
        refreshOrders();
        push({
          message: p.reason === 'REJECTED' ? 'Lệnh bị từ chối' : 'Lệnh đã hủy',
          variant: 'warning',
        });
      }),

      // ── Wallet events (user-scoped `wallet` channel) ─────────────────────────
      // Payload is a raw delta transaction with no absolute balance, so refetch
      // the authoritative balance instead of applying the delta locally.
      ws.onSchema('wallet.events.v1.WalletTransactionRecorded', () => {
        queryClient.invalidateQueries({ queryKey: ['wallets'] });
      }),
    ];

    return () => unsubs.forEach((u) => u());
  }, [ws, queryClient, push, tradeStore, ordersStore, walletStore]);

  return null;
}
