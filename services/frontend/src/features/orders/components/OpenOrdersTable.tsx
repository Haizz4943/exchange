'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/lib/api/endpoints/orders';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { Table } from '@/components/ui/Table';
import { Skeleton } from '@/components/ui/Skeleton';
import { formatPrice, formatQuantity } from '@/lib/utils/bigNumber';
import { formatDateTime, orderStateLabel } from '@/lib/utils/format';
import type { OrderResponse } from '@/lib/api/endpoints/orders';

export function OpenOrdersTable() {
  const { apiClient } = useAuthContext();

  const { data, isLoading, error } = useQuery({
    queryKey: ['orders', 'list', { state: 'NEW,OPEN,PARTIALLY_FILLED' }],
    queryFn: () =>
      ordersApi(apiClient).list({ state: 'NEW,OPEN,PARTIALLY_FILLED', size: 50 }),
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
        r.limit_price ? formatPrice(r.limit_price) : 'Market',
      align: 'right' as const,
    },
    {
      key: 'filled',
      header: 'Filled',
      render: (r: OrderResponse) => formatQuantity(r.filled_qty),
      align: 'right' as const,
    },
    {
      key: 'state',
      header: 'State',
      render: (r: OrderResponse) => (
        <span className="hx-rounded-full hx-bg-blue-100 dark:hx-bg-blue-900/30 hx-text-blue-700 dark:hx-text-blue-300 hx-px-2 hx-py-0.5 hx-text-xs">
          {orderStateLabel(r.state)}
        </span>
      ),
    },
    {
      key: 'created',
      header: 'Time',
      render: (r: OrderResponse) => formatDateTime(r.created_at),
    },
  ];

  return (
    <div className="hx-p-6">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-4">Open Orders</h2>

      {error && (
        <div className="hx-rounded hx-bg-amber-50 dark:hx-bg-amber-900/20 hx-border hx-border-amber-200 hx-p-3 hx-text-sm hx-text-amber-700 dark:hx-text-amber-400 hx-mb-4">
          Order Service not yet available — showing stub data.
        </div>
      )}

      {isLoading ? (
        <table className="hx-w-full hx-text-sm">
          <tbody>
            {Array.from({ length: 5 }).map((_, i) => (
              <tr key={i} className="hx-border-b hx-border-gray-100 dark:hx-border-gray-800">
                {Array.from({ length: 8 }).map((__, j) => (
                  <td key={j} className="hx-px-3 hx-py-2">
                    <Skeleton height={14} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Table
          columns={columns}
          rows={data?.content ?? []}
          keyExtractor={(r) => r.order_id}
          emptyMessage="No open orders. Place your first order to start trading."
          stickyHeader
        />
      )}
    </div>
  );
}
