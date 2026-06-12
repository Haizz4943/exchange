'use client';

import React, { createContext, useContext, useEffect, useMemo, useRef } from 'react';
import { WsClient } from './WsClient';
import { usePanelConfig } from '@/lib/config/PanelConfig';
import { useAuthContext } from '@/lib/auth/AuthContext';

const WsContext = createContext<WsClient | null>(null);

export function WsProvider({ children }: { children: React.ReactNode }) {
  const { gatewayBaseUrl } = usePanelConfig();
  const { accessToken } = useAuthContext();
  const tokenRef = useRef(accessToken);
  tokenRef.current = accessToken;

  const wsClient = useMemo(
    () =>
      new WsClient(
        // Convert http(s):// to ws(s)://
        gatewayBaseUrl.replace(/^http/, 'ws'),
        () => tokenRef.current,
      ),
    // Create a new client when gatewayBaseUrl changes (edge case: prop update)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [gatewayBaseUrl],
  );

  useEffect(() => {
    // Only connect when we have a token (or the gateway is public-ws)
    if (accessToken) {
      wsClient.connect();
    }
    return () => {
      wsClient.close();
    };
  }, [wsClient, accessToken]);

  return <WsContext.Provider value={wsClient}>{children}</WsContext.Provider>;
}

export function useWsClient(): WsClient {
  const ctx = useContext(WsContext);
  if (!ctx) throw new Error('useWsClient must be inside WsProvider');
  return ctx;
}
