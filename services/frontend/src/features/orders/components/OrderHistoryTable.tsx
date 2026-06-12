'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/lib/api/endpoints/orders';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { Table } from '@/components/ui/Table';
import { formatPrice, formatQuantity } from '@/lib/utils/bigNumber';
import { formatDateTime, orderStateLabel } from '@/lib/utils/format';
import type { OrderResponse } from '@/lib/api/endpoints/orders';

export function OrderHistoryTable() {
  const { apiClient } = useAuthContext();

  const { data, isLoading, error } = useQuery({
    queryKey: ['orders', 'list', { state: 'FILLED,CANCELLED,REJECTED' }],
    queryFn: () =>
      ordersApi(apiClient).list({ state: 'FILLED,CANCELLED,REJECTED', size: 50, sort: 'created_at,desc' }),
    staleTime: 30_000,
    retry: 1,
  });

  const columns = [
    { key: 'pair', header: 'Pair', render: (r: OrderResponse) => r.pair },
    {
      key: 'side',
      header: 'Side',
      render: (r: OrderResponse) => (
        <span className={r.side === 'BUY' ? 'hx-text-green-500' : 'hx-text-red-500'}>
          {r.side}
        </span>
      ),
    },
    { key: 'type', header: 'Type', render: (r: OrderResponse) => r.type },
    {
      key: 'quantity',
      header: 'Qty',
      render: (r: OrderResponse) => formatQuantity(r.quantity),
      align: 'right' as const,
    },
    {
      key: 'price',
      header: 'Price',
      render: (r: OrderResponse) =>
        r.avg_fill_price ? formatPrice(r.avg_fill_price) : r.limit_price ? formatPrice(r.limit_price) : 'Market',
      align: 'right' as const,
    },
    {
      key: 'state',
      header: 'State',
      render: (r: OrderResponse) => {
        const color =
          r.state === 'FILLED'
            ? 'hx-text-green-600 dark:hx-text-green-400 hx-bg-green-100 dark:hx-bg-green-900/20'
            : r.state === 'CANCELLED'
            ? 'hx-text-gray-500 hx-bg-gray-100 dark:hx-bg-gray-800'
            : 'hx-text-red-600 dark:hx-text-red-400 hx-bg-red-100 dark:hx-bg-red-900/20';
        return (
          <span className={`hx-rounded-full hx-px-2 hx-py-0.5 hx-text-xs ${color}`}>
            {orderStateLabel(r.state)}
          </span>
        );
      },
    },
    {
      key: 'created',
      header: 'Time',
      render: (r: OrderResponse) => formatDateTime(r.created_at),
    },
  ];

  return (
    <div className="hx-p-6">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-4">Order History</h2>

      {error && (
        <div className="hx-rounded hx-bg-amber-50 dark:hx-bg-amber-900/20 hx-border hx-border-amber-200 hx-p-3 hx-text-sm hx-text-amber-700 dark:hx-text-amber-400 hx-mb-4">
          Order Service not yet available.
        </div>
      )}

      <Table
        columns={columns}
        rows={data?.content ?? []}
        keyExtractor={(r) => r.order_id}
        emptyMessage={isLoading ? 'Loading...' : 'Your executed trades will appear here.'}
        stickyHeader
      />
    </div>
  );
}
