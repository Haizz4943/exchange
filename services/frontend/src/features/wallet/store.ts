'use client';

import { create } from 'zustand';
import { createContext, useContext } from 'react';
import { WalletBalance } from '@/lib/api/endpoints/wallets';

export interface BalanceChangeEvent {
  assetCode: string;
  balanceAfter?: string;
  available?: string;
  frozen?: string;
}

export interface WalletState {
  wallets: WalletBalance[];
  lastUpdated: number | null;

  setWallets: (wallets: WalletBalance[]) => void;
  applyBalanceChange: (event: BalanceChangeEvent) => void;
}

export type WalletStore = ReturnType<typeof createWalletStore>;

export const createWalletStore = () =>
  create<WalletState>((set) => ({
    wallets: [],
    lastUpdated: null,

    setWallets: (wallets) => set({ wallets, lastUpdated: Date.now() }),

    applyBalanceChange: (event) =>
      set((s) => ({
        wallets: s.wallets.map((w) => {
          if (w.assetCode !== event.assetCode) return w;
          return {
            ...w,
            ...(event.available !== undefined ? { available: event.available } : {}),
            ...(event.frozen !== undefined ? { frozen: event.frozen } : {}),
            ...(event.balanceAfter !== undefined ? { total: event.balanceAfter } : {}),
          };
        }),
        lastUpdated: Date.now(),
      })),
  }));

// ── Context ───────────────────────────────────────────────────────────────────

export const WalletStoreContext = createContext<WalletStore | null>(null);

export function useWalletStoreRaw(): WalletStore {
  const store = useContext(WalletStoreContext);
  if (!store) throw new Error('useWalletStoreRaw must be inside WalletStoreProvider');
  return store;
}

export function useWalletStore<T>(selector: (s: WalletState) => T): T {
  return useWalletStoreRaw()(selector);
}
