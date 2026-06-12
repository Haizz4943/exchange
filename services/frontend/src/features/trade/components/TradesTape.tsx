'use client';

import React from 'react';
import { useWsSubscription } from '@/lib/ws/useWsSubscription';
import { useTradeStore } from '@/features/trade/store';
import { Skeleton } from '@/components/ui/Skeleton';
import { formatPrice, formatQuantity } from '@/lib/utils/bigNumber';
import { formatTime } from '@/lib/utils/format';

interface TradesTapeProps {
  pair: string;
}

export function TradesTape({ pair }: TradesTapeProps) {
  useWsSubscription(`market:${pair}:trades`);
  const trades = useTradeStore((s) => s.recentTrades[pair] ?? []);

  return (
    <div className="hx-flex hx-flex-col hx-h-full">
      <p className="hx-text-xs hx-font-medium hx-text-gray-500 hx-px-3 hx-py-2 hx-border-b hx-border-gray-200 dark:hx-border-gray-800">
        Recent Trades
      </p>

      {trades.length === 0 && (
        <div className="hx-p-3">
          <div className="hx-text-xs hx-text-amber-400 hx-bg-amber-50 dark:hx-bg-amber-900/20 hx-border hx-border-amber-200 dark:hx-border-amber-800 hx-rounded hx-p-2 hx-mb-3">
            Live trades require WebSocket Gateway (not yet deployed)
          </div>
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} height={16} className="hx-w-full hx-mb-1" />
          ))}
        </div>
      )}

      <div className="hx-overflow-y-auto hx-flex-1">
        <div className="hx-grid hx-grid-cols-3 hx-px-3 hx-py-1 hx-text-xs hx-text-gray-400">
          <span>Price</span>
          <span className="hx-text-right">Qty</span>
          <span className="hx-text-right">Time</span>
        </div>
        {trades.map((trade, i) => (
          <div
            key={i}
            className="hx-grid hx-grid-cols-3 hx-px-3 hx-py-0.5 hx-text-xs hx-font-mono"
          >
            <span className="hx-text-green-500">{formatPrice(trade.price)}</span>
            <span className="hx-text-right hx-text-gray-400">
              {formatQuantity(trade.quantity)}
            </span>
            <span className="hx-text-right hx-text-gray-500">
              {formatTime(trade.executedAt)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
