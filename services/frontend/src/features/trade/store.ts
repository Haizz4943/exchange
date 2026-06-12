'use client';

import { create } from 'zustand';
import { createContext, useContext } from 'react';

export interface DepthSnapshot {
  bids: [string, string][];
  asks: [string, string][];
}

export interface RecentTrade {
  pair: string;
  price: string;
  quantity: string;
  executedAt: string;
}

export interface Ticker {
  pair: string;
  best_bid: string;
  best_ask: string;
  last_price: string;
  updated_at: string;
}

export interface TradeState {
  currentPair: string;
  depth: Record<string, DepthSnapshot>;
  ticker: Record<string, Ticker>;
  recentTrades: Record<string, RecentTrade[]>;

  setCurrentPair: (pair: string) => void;
  applyDepthUpdate: (pair: string, depth: DepthSnapshot) => void;
  applyTickerUpdate: (pair: string, ticker: Ticker) => void;
  appendRecentTrade: (pair: string, trade: RecentTrade) => void;
}

export type TradeStore = ReturnType<typeof createTradeStore>;

export const createTradeStore = () =>
  create<TradeState>((set) => ({
    currentPair: process.env.NEXT_PUBLIC_DEFAULT_PAIR ?? 'BTCUSDT',
    depth: {},
    ticker: {},
    recentTrades: {},

    setCurrentPair: (pair) => set({ currentPair: pair }),

    applyDepthUpdate: (pair, depth) =>
      set((s) => ({ depth: { ...s.depth, [pair]: depth } })),

    applyTickerUpdate: (pair, ticker) =>
      set((s) => ({ ticker: { ...s.ticker, [pair]: ticker } })),

    appendRecentTrade: (pair, trade) =>
      set((s) => ({
        recentTrades: {
          ...s.recentTrades,
          [pair]: [trade, ...(s.recentTrades[pair] ?? [])].slice(0, 100),
        },
      })),
  }));

// ── Context (instance-scoped per panel mount) ─────────────────────────────────

export const TradeStoreContext = createContext<TradeStore | null>(null);

export function useTradeStoreRaw(): TradeStore {
  const store = useContext(TradeStoreContext);
  if (!store) throw new Error('useTradeStoreRaw must be inside TradeStoreProvider');
  return store;
}

export function useTradeStore<T>(selector: (s: TradeState) => T): T {
  const store = useTradeStoreRaw();
  return store(selector);
}
