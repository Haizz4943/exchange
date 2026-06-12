'use client';

import React, { useMemo } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PanelConfigProvider } from '@/lib/config/PanelConfig';
import { AuthBridgeProvider } from '@/lib/auth/AuthBridge';
import { WsProvider } from '@/lib/ws/WsProvider';
import { PanelStoreProviders } from './PanelProviders';
import { PanelRouter } from './PanelRouter';
import { ErrorBoundary } from '@/components/layout/ErrorBoundary';
import type { PanelRoute, HaizzEvent } from '@/lib/config/types';

export interface HaizzTradingPanelProps {
  mode: 'standalone' | 'embedded';
  auth?: {
    accessToken: string;
    refreshCallback?: () => Promise<string>;
    onAuthExpired?: () => void;
  };
  gatewayBaseUrl?: string;
  theme?: 'light' | 'dark' | 'inherit';
  locale?: string;
  initialRoute?: PanelRoute;
  onEvent?: (event: HaizzEvent) => void;
}

const queryClientOptions = {
  defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
};

function PanelError({ message }: { message: string }) {
  return (
    <div
      style={{
        padding: '1rem',
        border: '1px solid #ef5350',
        borderRadius: '4px',
        color: '#ef5350',
        fontFamily: 'monospace',
      }}
    >
      HaizzTradingPanel error: {message}
    </div>
  );
}

export function HaizzTradingPanel(props: HaizzTradingPanelProps) {
  // Create a fresh QueryClient per mount — each embed is independent
  const queryClient = useMemo(() => new QueryClient(queryClientOptions), []);

  const gatewayBaseUrl =
    props.gatewayBaseUrl ??
    (props.mode === 'standalone' ? process.env.NEXT_PUBLIC_GATEWAY_URL : undefined);

  if (!gatewayBaseUrl) {
    return <PanelError message="Missing gatewayBaseUrl prop (required in embedded mode)" />;
  }

  return (
    <div className="haizz-panel" data-theme={props.theme ?? 'light'} data-mode={props.mode}>
      <PanelConfigProvider
        value={{
          mode: props.mode,
          gatewayBaseUrl,
          locale: props.locale,
          theme: props.theme,
          onEvent: props.onEvent,
        }}
      >
        <QueryClientProvider client={queryClient}>
          <AuthBridgeProvider auth={props.auth} mode={props.mode}>
            <WsProvider>
              <PanelStoreProviders>
                <ErrorBoundary
                  onError={(err, info) =>
                    props.onEvent?.({ type: 'error', error: err, info: info ?? undefined })
                  }
                >
                  <PanelRouter initialRoute={props.initialRoute} />
                </ErrorBoundary>
              </PanelStoreProviders>
            </WsProvider>
          </AuthBridgeProvider>
        </QueryClientProvider>
      </PanelConfigProvider>
    </div>
  );
}

export default HaizzTradingPanel;
