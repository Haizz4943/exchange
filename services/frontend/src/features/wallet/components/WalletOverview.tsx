'use client';

import React, { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { walletsApi } from '@/lib/api/endpoints/wallets';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { useWalletStore, useWalletStoreRaw } from '@/features/wallet/store';
import { Skeleton } from '@/components/ui/Skeleton';
import { formatDecimal } from '@/lib/utils/bigNumber';

export function WalletOverview() {
  const { apiClient } = useAuthContext();
  const setWallets = useWalletStoreRaw()((s) => s.setWallets);
  const wallets = useWalletStore((s) => s.wallets);

  const { data, isLoading, error } = useQuery({
    queryKey: ['wallets', 'me'],
    queryFn: () => walletsApi(apiClient).getMyWallets(),
    staleTime: 30_000,
    retry: 1,
  });

  // Sync to Zustand so WS updates can modify without re-fetching
  useEffect(() => {
    if (data?.wallets) setWallets(data.wallets);
  }, [data, setWallets]);

  const displayWallets = wallets.length > 0 ? wallets : data?.wallets ?? [];

  return (
    <div className="hx-p-6 hx-max-w-3xl">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-6 hx-text-gray-900 dark:hx-text-white">
        My Wallets
      </h2>

      {error && (
        <div className="hx-rounded hx-bg-red-50 dark:hx-bg-red-900/20 hx-border hx-border-red-200 dark:hx-border-red-800 hx-p-3 hx-text-sm hx-text-red-700 dark:hx-text-red-400 hx-mb-4">
          Failed to load wallets. The Wallet Service may be unavailable.
        </div>
      )}

      <div className="hx-grid hx-grid-cols-1 md:hx-grid-cols-2 lg:hx-grid-cols-3 hx-gap-4">
        {isLoading && !displayWallets.length
          ? Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="hx-rounded-lg hx-border hx-border-gray-200 dark:hx-border-gray-800 hx-p-4 hx-bg-white dark:hx-bg-gray-900">
                <Skeleton height={20} className="hx-w-16 hx-mb-3" />
                <Skeleton height={28} className="hx-w-32 hx-mb-2" />
                <Skeleton height={14} className="hx-w-24" />
              </div>
            ))
          : displayWallets.map((wallet) => (
              <div
                key={wallet.walletId}
                className="hx-rounded-lg hx-border hx-border-gray-200 dark:hx-border-gray-800 hx-p-4 hx-bg-white dark:hx-bg-gray-900"
              >
                <p className="hx-text-sm hx-font-medium hx-text-gray-500 dark:hx-text-gray-400">
                  {wallet.assetCode}
                </p>
                <p className="hx-text-xl hx-font-semibold hx-text-gray-900 dark:hx-text-white hx-mt-1">
                  {formatDecimal(wallet.total, wallet.assetCode === 'USDT' ? 2 : 8)}
                </p>
                <div className="hx-mt-2 hx-flex hx-gap-4 hx-text-xs hx-text-gray-400">
                  <span>
                    Available:{' '}
                    <span className="hx-text-green-500">
                      {formatDecimal(wallet.available, wallet.assetCode === 'USDT' ? 2 : 8)}
                    </span>
                  </span>
                  <span>
                    Frozen:{' '}
                    <span className="hx-text-red-400">
                      {formatDecimal(wallet.frozen, wallet.assetCode === 'USDT' ? 2 : 8)}
                    </span>
                  </span>
                </div>
              </div>
            ))}
      </div>

      {displayWallets.length === 0 && !isLoading && !error && (
        <p className="hx-text-gray-500 hx-text-sm">Your account is being set up...</p>
      )}
    </div>
  );
}
