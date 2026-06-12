'use client';

import { createContext, useContext } from 'react';
import { ApiClient } from '@/lib/api/client';

export interface AuthContextValue {
  accessToken: string | null;
  refreshToken: string | null;
  setAccessToken: (token: string | null) => void;
  setRefreshToken: (token: string | null) => void;
  apiClient: ApiClient;
  isAuthed: boolean;
  user: { user_id: string; email: string } | null;
  setUser: (user: { user_id: string; email: string } | null) => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuthContext(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuthContext must be inside AuthStandaloneProvider or AuthBridgeProvider');
  return ctx;
}
