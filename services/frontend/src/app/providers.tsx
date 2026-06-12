'use client';

import React, { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PanelConfigProvider } from '@/lib/config/PanelConfig';
import { AuthStandaloneProvider } from '@/lib/auth/AuthStandaloneProvider';
import { WsProvider } from '@/lib/ws/WsProvider';
import { PanelStoreProviders } from '@/panel/PanelProviders';
import { ToastProvider } from '@/components/ui/Toast';

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
      }),
  );

  return (
    <PanelConfigProvider
      value={{
        mode: 'standalone',
        gatewayBaseUrl: process.env.NEXT_PUBLIC_GATEWAY_URL ?? 'http://localhost:8080',
        defaultPair: process.env.NEXT_PUBLIC_DEFAULT_PAIR ?? 'BTCUSDT',
        supportedPairs: (process.env.NEXT_PUBLIC_SUPPORTED_PAIRS ?? 'BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT').split(','),
      }}
    >
      <QueryClientProvider client={queryClient}>
        <AuthStandaloneProvider>
          <WsProvider>
            <PanelStoreProviders>
              <ToastProvider>{children}</ToastProvider>
            </PanelStoreProviders>
          </WsProvider>
        </AuthStandaloneProvider>
      </QueryClientProvider>
    </PanelConfigProvider>
  );
}
