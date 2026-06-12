'use client';

import React, { useMemo, useRef, useState } from 'react';
import { ApiClient } from '@/lib/api/client';
import { usePanelConfig } from '@/lib/config/PanelConfig';
import { AuthContext, AuthContextValue } from './AuthContext';

export function AuthStandaloneProvider({ children }: { children: React.ReactNode }) {
  const { gatewayBaseUrl } = usePanelConfig();
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState<string | null>(null);
  const [user, setUser] = useState<{ user_id: string; email: string } | null>(null);

  // Refs so closures inside useMemo always see latest values without recreating the client
  const accessTokenRef = useRef<string | null>(accessToken);
  accessTokenRef.current = accessToken;
  const refreshTokenRef = useRef<string | null>(refreshToken);
  refreshTokenRef.current = refreshToken;
  const gatewayUrlRef = useRef(gatewayBaseUrl);
  gatewayUrlRef.current = gatewayBaseUrl;

  // Mutable ref for the refresh function itself
  const refreshFnRef = useRef<() => Promise<string>>(async () => {
    throw new Error('Not initialized');
  });

  // Create ApiClient once per gatewayBaseUrl; all closures read from refs
  const apiClient = useMemo(
    () =>
      new ApiClient({
        baseUrl: gatewayBaseUrl,
        getAccessToken: () => accessTokenRef.current,
        onUnauthorized: async () => {
          try {
            await refreshFnRef.current();
          } catch {
            setAccessToken(null);
            setRefreshToken(null);
            setUser(null);
          }
        },
      }),
    [gatewayBaseUrl],
  );

  // Keep refreshFnRef up-to-date so onUnauthorized always calls latest logic
  refreshFnRef.current = async (): Promise<string> => {
    const rt = refreshTokenRef.current;
    if (!rt) throw new Error('No refresh token available');
    const res = await fetch(`${gatewayUrlRef.current}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: rt }),
    });
    if (!res.ok) throw new Error('Refresh failed');
    const data: { access_token: string; refresh_token: string } = await res.json();
    setAccessToken(data.access_token);
    setRefreshToken(data.refresh_token);
    return data.access_token;
  };

  const value: AuthContextValue = {
    accessToken,
    refreshToken,
    setAccessToken,
    setRefreshToken,
    apiClient,
    isAuthed: !!accessToken,
    user,
    setUser,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
