'use client';

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { tradesApi } from '@/lib/api/endpoints/trades';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { Table } from '@/components/ui/Table';
import { formatPrice, formatQuantity } from '@/lib/utils/bigNumber';
import { formatDateTime } from '@/lib/utils/format';
import type { TradeRecord } from '@/lib/api/endpoints/trades';

export function TradeHistoryTable() {
  const { apiClient } = useAuthContext();

  const { data, isLoading, error } = useQuery({
    queryKey: ['trades', 'list', {}],
    queryFn: () => tradesApi(apiClient).list({ size: 50 }),
    staleTime: 30_000,
    retry: 1,
  });

  const columns = [
    { key: 'pair', header: 'Pair', render: (r: TradeRecord) => r.pair },
    {
      key: 'side',
      header: 'Side',
      render: (r: TradeRecord) => (
        <span className={r.side === 'BUY' ? 'hx-text-green-500' : 'hx-text-red-500'}>
          {r.side}
        </span>
      ),
    },
    {
      key: 'price',
      header: 'Price',
      render: (r: TradeRecord) => formatPrice(r.price),
      align: 'right' as const,
    },
    {
      key: 'quantity',
      header: 'Qty',
      render: (r: TradeRecord) => formatQuantity(r.quantity),
      align: 'right' as const,
    },
    {
      key: 'quoteQty',
      header: 'Total (USDT)',
      render: (r: TradeRecord) => formatPrice(r.quoteQuantity),
      align: 'right' as const,
    },
    {
      key: 'fee',
      header: 'Fee',
      render: (r: TradeRecord) => `${formatPrice(r.fee)} ${r.feeAsset}`,
      align: 'right' as const,
    },
    { key: 'role', header: 'Role', render: (r: TradeRecord) => r.role },
    {
      key: 'executedAt',
      header: 'Time',
      render: (r: TradeRecord) => formatDateTime(r.executedAt),
    },
  ];

  return (
    <div className="hx-p-6">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-4">Trade History</h2>

      {error && (
        <div className="hx-rounded hx-bg-amber-50 dark:hx-bg-amber-900/20 hx-border hx-border-amber-200 hx-p-3 hx-text-sm hx-text-amber-700 dark:hx-text-amber-400 hx-mb-4">
          Trade Service not yet available.
        </div>
      )}

      <Table
        columns={columns}
        rows={data?.content ?? []}
        keyExtractor={(r) => r.tradeId}
        emptyMessage={isLoading ? 'Loading...' : 'Your executed trades will appear here.'}
        stickyHeader
      />
    </div>
  );
}
