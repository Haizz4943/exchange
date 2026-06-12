'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { ApiClient } from '@/lib/api/client';
import { usePanelConfig } from '@/lib/config/PanelConfig';
import { AuthContext, AuthContextValue } from './AuthContext';
import { AuthStandaloneProvider } from './AuthStandaloneProvider';

interface AuthBridgeProps {
  auth?: {
    accessToken: string;
    refreshCallback?: () => Promise<string>;
    onAuthExpired?: () => void;
  };
  mode: 'standalone' | 'embedded';
  children: React.ReactNode;
}

export function AuthBridgeProvider({ auth, mode, children }: AuthBridgeProps) {
  if (mode === 'standalone') {
    return <AuthStandaloneProvider>{children}</AuthStandaloneProvider>;
  }

  // Embedded mode — host provides auth
  return (
    <EmbeddedAuthProvider auth={auth} mode={mode}>
      {children}
    </EmbeddedAuthProvider>
  );
}

function EmbeddedAuthProvider({ auth, children }: AuthBridgeProps) {
  const { gatewayBaseUrl } = usePanelConfig();
  const [accessToken, setAccessToken] = useState<string | null>(auth?.accessToken ?? null);
  const [user, setUser] = useState<{ user_id: string; email: string } | null>(null);

  // Sync prop changes (host refreshed externally)
  useEffect(() => {
    setAccessToken(auth?.accessToken ?? null);
  }, [auth?.accessToken]);

  const refresh = async (): Promise<string> => {
    if (!auth?.refreshCallback) {
      auth?.onAuthExpired?.();
      throw new Error('No refresh callback provided; host must handle re-auth');
    }
    const newToken = await auth.refreshCallback();
    setAccessToken(newToken);
    return newToken;
  };

  const apiClient = useMemo(
    () =>
      new ApiClient({
        baseUrl: gatewayBaseUrl,
        getAccessToken: () => accessToken,
        onUnauthorized: async () => {
          try {
            await refresh();
          } catch {
            auth?.onAuthExpired?.();
          }
        },
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [gatewayBaseUrl, accessToken],
  );

  const value: AuthContextValue = {
    accessToken,
    refreshToken: null,
    setAccessToken,
    setRefreshToken: () => {},
    apiClient,
    isAuthed: !!accessToken,
    user,
    setUser,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
