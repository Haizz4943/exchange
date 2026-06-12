'use client';

import { create } from 'zustand';
import { createContext, useContext } from 'react';
import { OrderResponse, OrderState } from '@/lib/api/endpoints/orders';

export interface FillUpdate {
  order_id: string;
  state?: OrderState;
  filled_qty?: string;
  avg_fill_price?: string;
}

export interface OrdersState {
  openOrders: Record<string, OrderResponse>;

  setOpenOrders: (orders: OrderResponse[]) => void;
  applyFillUpdate: (update: FillUpdate) => void;
  removeOrder: (orderId: string) => void;
}

export type OrdersStore = ReturnType<typeof createOrdersStore>;

export const createOrdersStore = () =>
  create<OrdersState>((set) => ({
    openOrders: {},

    setOpenOrders: (orders) =>
      set({
        openOrders: Object.fromEntries(orders.map((o) => [o.order_id, o])),
      }),

    applyFillUpdate: (update) =>
      set((s) => {
        const existing = s.openOrders[update.order_id];
        if (!existing) return s;
        const updated: OrderResponse = {
          ...existing,
          ...(update.state ? { state: update.state } : {}),
          ...(update.filled_qty ? { filled_qty: update.filled_qty } : {}),
          ...(update.avg_fill_price ? { avg_fill_price: update.avg_fill_price } : {}),
        };
        // Remove from openOrders if in terminal state
        const terminalStates: OrderState[] = ['FILLED', 'CANCELLED', 'REJECTED'];
        if (updated.state && terminalStates.includes(updated.state)) {
          const { [update.order_id]: _, ...rest } = s.openOrders;
          return { openOrders: rest };
        }
        return { openOrders: { ...s.openOrders, [update.order_id]: updated } };
      }),

    removeOrder: (orderId) =>
      set((s) => {
        const { [orderId]: _, ...rest } = s.openOrders;
        return { openOrders: rest };
      }),
  }));

// ── Context ───────────────────────────────────────────────────────────────────

export const OrdersStoreContext = createContext<OrdersStore | null>(null);

export function useOrdersStoreRaw(): OrdersStore {
  const store = useContext(OrdersStoreContext);
  if (!store) throw new Error('useOrdersStoreRaw must be inside OrdersStoreProvider');
  return store;
}

export function useOrdersStore<T>(selector: (s: OrdersState) => T): T {
  return useOrdersStoreRaw()(selector);
}
