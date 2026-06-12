'use client';

import { useState } from 'react';
import { authApi } from '@/lib/api/endpoints/auth';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { ApiError } from '@/lib/api/errors';

interface LoginFormData {
  email: string;
  password: string;
}

export function useLogin() {
  const { apiClient, setAccessToken, setRefreshToken, setUser } = useAuthContext();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const login = async (data: LoginFormData) => {
    setLoading(true);
    setError(null);
    try {
      const res = await authApi(apiClient).login(data);
      setAccessToken(res.access_token);
      setRefreshToken(res.refresh_token);
      setUser(res.user);
      return res;
    } catch (err) {
      const msg =
        err instanceof ApiError ? err.userMessage : 'Login failed. Please try again.';
      setError(msg);
      throw err;
    } finally {
      setLoading(false);
    }
  };

  return { login, loading, error };
}
