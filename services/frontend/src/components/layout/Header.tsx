'use client';

import React from 'react';
import Link from 'next/link';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { authApi } from '@/lib/api/endpoints/auth';
import { useNavigation } from '@/lib/navigation/useNavigation';
import { Button } from '@/components/ui/Button';
import { TrendingUp } from 'lucide-react';

export function Header() {
  const { apiClient, isAuthed, user, setAccessToken, setRefreshToken, setUser, refreshToken } =
    useAuthContext();
  const { navigate } = useNavigation();

  const handleLogout = async () => {
    try {
      if (refreshToken) {
        await authApi(apiClient).logout({ refresh_token: refreshToken });
      }
    } catch {
      // Best-effort logout
    } finally {
      setAccessToken(null);
      setRefreshToken(null);
      setUser(null);
      navigate({ screen: 'login' });
    }
  };

  return (
    <header className="hx-h-14 hx-border-b hx-border-gray-200 dark:hx-border-gray-800 hx-px-4 hx-flex hx-items-center hx-justify-between hx-bg-white dark:hx-bg-gray-900">
      <div className="hx-flex hx-items-center hx-gap-2">
        <TrendingUp className="hx-h-5 hx-w-5 hx-text-blue-600" />
        <span className="hx-font-bold hx-text-gray-900 dark:hx-text-white">Haizz Exchange</span>
      </div>

      {isAuthed && (
        <nav className="hx-hidden md:hx-flex hx-items-center hx-gap-4 hx-text-sm">
          <Link
            href="/trade"
            className="hx-text-gray-600 hover:hx-text-blue-600 dark:hx-text-gray-300"
          >
            Trade
          </Link>
          <Link
            href="/wallet"
            className="hx-text-gray-600 hover:hx-text-blue-600 dark:hx-text-gray-300"
          >
            Wallet
          </Link>
          <Link
            href="/orders"
            className="hx-text-gray-600 hover:hx-text-blue-600 dark:hx-text-gray-300"
          >
            Orders
          </Link>
          <Link
            href="/trades"
            className="hx-text-gray-600 hover:hx-text-blue-600 dark:hx-text-gray-300"
          >
            Trade History
          </Link>
        </nav>
      )}

      <div className="hx-flex hx-items-center hx-gap-3">
        {isAuthed ? (
          <>
            <span className="hx-text-xs hx-text-gray-500">{user?.email}</span>
            <Button variant="ghost" size="sm" onClick={handleLogout}>
              Logout
            </Button>
          </>
        ) : (
          <Link href="/login">
            <Button size="sm">Login</Button>
          </Link>
        )}
      </div>
    </header>
  );
}
