'use client';

import React from 'react';
import { useWsSubscription } from '@/lib/ws/useWsSubscription';
import { useTradeStore } from '@/features/trade/store';
import { Skeleton } from '@/components/ui/Skeleton';
import { formatPrice, formatQuantity } from '@/lib/utils/bigNumber';

export interface OrderBookProps {
  pair: string;
  levels?: number;
}

export function OrderBook({ pair, levels = 15 }: OrderBookProps) {
  // Subscribe declaratively — WsStoreSyncer handles dispatching to the store
  useWsSubscription(`market:${pair}:depth`);
  const depth = useTradeStore((s) => s.depth[pair]);

  if (!depth) {
    return (
      <div className="hx-p-3 hx-flex hx-flex-col hx-gap-1">
        <p className="hx-text-xs hx-text-gray-500 hx-mb-2">Order Book</p>
        <div className="hx-text-xs hx-text-amber-400 hx-bg-amber-50 dark:hx-bg-amber-900/20 hx-border hx-border-amber-200 dark:hx-border-amber-800 hx-rounded hx-p-2 hx-mb-3">
          Live data requires WebSocket Gateway (not yet deployed)
        </div>
        {Array.from({ length: 6 }).map((_, i) => (
          <Skeleton key={i} height={16} className="hx-w-full" />
        ))}
      </div>
    );
  }

  const bids = depth.bids.slice(0, levels);
  const asks = depth.asks.slice(0, levels);

  const maxSize = Math.max(
    ...bids.map(([, q]) => parseFloat(q)),
    ...asks.map(([, q]) => parseFloat(q)),
    0.001,
  );

  return (
    <div className="hx-flex hx-flex-col hx-text-xs hx-font-mono hx-overflow-hidden">
      {/* Header */}
      <div className="hx-grid hx-grid-cols-3 hx-px-2 hx-py-1 hx-text-gray-400 hx-border-b hx-border-gray-200 dark:hx-border-gray-800">
        <span>Price</span>
        <span className="hx-text-right">Qty</span>
        <span className="hx-text-right">Total</span>
      </div>

      {/* Asks (ascending — reverse so highest ask is top) */}
      <div className="hx-flex hx-flex-col-reverse">
        {asks.map(([price, qty], i) => {
          const p = parseFloat(price);
          const q = parseFloat(qty);
          const barW = Math.min((q / maxSize) * 100, 100);
          return (
            <div
              key={`ask-${i}`}
              className="hx-relative hx-grid hx-grid-cols-3 hx-px-2 hx-py-0.5 hover:hx-bg-red-50 dark:hover:hx-bg-red-900/10 hx-cursor-pointer"
            >
              <div
                className="hx-absolute hx-right-0 hx-top-0 hx-bottom-0 hx-bg-red-100 dark:hx-bg-red-900/20"
                style={{ width: `${barW}%` }}
              />
              <span className="hx-relative hx-text-red-500">{formatPrice(p)}</span>
              <span className="hx-relative hx-text-right hx-text-gray-700 dark:hx-text-gray-300">
                {formatQuantity(q)}
              </span>
              <span className="hx-relative hx-text-right hx-text-gray-500">
                {formatPrice(p * q)}
              </span>
            </div>
          );
        })}
      </div>

      {/* Spread */}
      {bids[0] && asks[0] && (
        <div className="hx-px-2 hx-py-1 hx-text-center hx-text-gray-500 hx-border-y hx-border-gray-200 dark:hx-border-gray-800">
          Spread:{' '}
          {formatPrice(
            Math.abs(parseFloat(asks[0][0]) - parseFloat(bids[0][0])),
          )}
        </div>
      )}

      {/* Bids (descending) */}
      <div className="hx-flex hx-flex-col">
        {bids.map(([price, qty], i) => {
          const p = parseFloat(price);
          const q = parseFloat(qty);
          const barW = Math.min((q / maxSize) * 100, 100);
          return (
            <div
              key={`bid-${i}`}
              className="hx-relative hx-grid hx-grid-cols-3 hx-px-2 hx-py-0.5 hover:hx-bg-green-50 dark:hover:hx-bg-green-900/10 hx-cursor-pointer"
            >
              <div
                className="hx-absolute hx-right-0 hx-top-0 hx-bottom-0 hx-bg-green-100 dark:hx-bg-green-900/20"
                style={{ width: `${barW}%` }}
              />
              <span className="hx-relative hx-text-green-500">{formatPrice(p)}</span>
              <span className="hx-relative hx-text-right hx-text-gray-700 dark:hx-text-gray-300">
                {formatQuantity(q)}
              </span>
              <span className="hx-relative hx-text-right hx-text-gray-500">
                {formatPrice(p * q)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
