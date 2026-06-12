'use client';

import React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { clsx } from 'clsx';
import { TrendingUp, Wallet, List, BarChart2, ArrowDownToLine } from 'lucide-react';

const navItems = [
  { href: '/trade', label: 'Trade', icon: TrendingUp },
  { href: '/wallet', label: 'Wallet', icon: Wallet },
  { href: '/orders', label: 'Orders', icon: List },
  { href: '/trades', label: 'Trade History', icon: BarChart2 },
  { href: '/deposit', label: 'Deposit', icon: ArrowDownToLine },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="hx-w-56 hx-border-r hx-border-gray-200 dark:hx-border-gray-800 hx-bg-white dark:hx-bg-gray-900 hx-flex hx-flex-col hx-py-4">
      <nav className="hx-flex hx-flex-col hx-gap-1 hx-px-2">
        {navItems.map(({ href, label, icon: Icon }) => {
          const active = pathname.startsWith(href);
          return (
            <Link
              key={href}
              href={href}
              className={clsx(
                'hx-flex hx-items-center hx-gap-3 hx-rounded hx-px-3 hx-py-2 hx-text-sm hx-transition-colors',
                active
                  ? 'hx-bg-blue-50 hx-text-blue-600 dark:hx-bg-blue-900/20 dark:hx-text-blue-400'
                  : 'hx-text-gray-600 hover:hx-bg-gray-100 dark:hx-text-gray-300 dark:hover:hx-bg-gray-800',
              )}
            >
              <Icon className="hx-h-4 hx-w-4" />
              {label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
