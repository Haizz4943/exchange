'use client';

import { useState } from 'react';
import { authApi } from '@/lib/api/endpoints/auth';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { useNavigation } from '@/lib/navigation/useNavigation';

export function useLogout() {
  const { apiClient, setAccessToken, setRefreshToken, setUser, refreshToken } = useAuthContext();
  const { navigate } = useNavigation();
  const [loading, setLoading] = useState(false);

  const logout = async () => {
    setLoading(true);
    try {
      if (refreshToken) {
        await authApi(apiClient).logout({ refresh_token: refreshToken });
      }
    } catch {
      // Best-effort logout — clear state regardless
    } finally {
      setAccessToken(null);
      setRefreshToken(null);
      setUser(null);
      setLoading(false);
      navigate({ screen: 'login' });
    }
  };

  return { logout, loading };
}
