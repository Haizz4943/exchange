'use client';

import React from 'react';
import { useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { Header } from '@/components/layout/Header';
import { Sidebar } from '@/components/layout/Sidebar';

export default function TraderLayout({ children }: { children: React.ReactNode }) {
  const { isAuthed } = useAuthContext();
  const router = useRouter();

  useEffect(() => {
    if (!isAuthed) {
      router.replace('/login');
    }
  }, [isAuthed, router]);

  if (!isAuthed) {
    return null;
  }

  return (
    <div className="hx-flex hx-flex-col hx-h-screen hx-overflow-hidden">
      <Header />
      <div className="hx-flex hx-flex-1 hx-min-h-0">
        <Sidebar />
        <main className="hx-flex-1 hx-overflow-auto hx-bg-gray-50 dark:hx-bg-gray-950">
          {children}
        </main>
      </div>
    </div>
  );
}
