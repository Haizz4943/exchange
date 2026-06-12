'use client';

import React, { useState } from 'react';
import { CandlestickChart } from '@/features/chart/CandlestickChart';
import { OrderBook } from './OrderBook';
import { OrderForm } from './OrderForm';
import { TradesTape } from './TradesTape';
import { PairSelector } from './PairSelector';
import { useWsSubscription } from '@/lib/ws/useWsSubscription';
import { useTradeStore } from '@/features/trade/store';
import type { Resolution } from '@/lib/api/endpoints/marketData';
import { Select } from '@/components/ui/Select';
import { formatPrice } from '@/lib/utils/bigNumber';

interface TradeScreenProps {
  pair: string;
}

const RESOLUTION_OPTIONS: { value: Resolution; label: string }[] = [
  { value: '1m', label: '1m' },
  { value: '5m', label: '5m' },
  { value: '15m', label: '15m' },
  { value: '1h', label: '1h' },
  { value: '4h', label: '4h' },
  { value: '1d', label: '1D' },
];

export function TradeScreen({ pair }: TradeScreenProps) {
  const [resolution, setResolution] = useState<Resolution>('1h');

  // Subscribe to ticker for the pair
  useWsSubscription(`market:${pair}:ticker`);
  const ticker = useTradeStore((s) => s.ticker[pair]);

  return (
    <div className="hx-flex hx-flex-col hx-h-full hx-min-h-0">
      {/* Top bar: pair selector + ticker info */}
      <div className="hx-flex hx-items-center hx-gap-4 hx-px-4 hx-py-2 hx-border-b hx-border-gray-200 dark:hx-border-gray-800 hx-bg-white dark:hx-bg-gray-900">
        <PairSelector currentPair={pair} />

        {ticker ? (
          <>
            <span className="hx-text-lg hx-font-bold hx-text-gray-900 dark:hx-text-white">
              {formatPrice(ticker.last_price)}
            </span>
            <span className="hx-text-xs hx-text-gray-500">
              Bid: <span className="hx-text-green-500">{formatPrice(ticker.best_bid)}</span>
            </span>
            <span className="hx-text-xs hx-text-gray-500">
              Ask: <span className="hx-text-red-500">{formatPrice(ticker.best_ask)}</span>
            </span>
          </>
        ) : (
          <span className="hx-text-sm hx-text-gray-400">{pair} — awaiting market data</span>
        )}

        <div className="hx-ml-auto hx-flex hx-items-center hx-gap-1">
          {RESOLUTION_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setResolution(opt.value)}
              className={
                resolution === opt.value
                  ? 'hx-px-2 hx-py-1 hx-text-xs hx-rounded hx-bg-blue-600 hx-text-white'
                  : 'hx-px-2 hx-py-1 hx-text-xs hx-rounded hx-text-gray-500 hover:hx-bg-gray-100 dark:hover:hx-bg-gray-800'
              }
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Main layout: Chart (70%) + Order Book (30%) */}
      <div className="hx-flex hx-flex-1 hx-min-h-0">
        {/* Left: Chart */}
        <div className="hx-flex-[7] hx-flex hx-flex-col hx-min-h-0 hx-border-r hx-border-gray-200 dark:hx-border-gray-800">
          <CandlestickChart pair={pair} resolution={resolution} height={400} />
        </div>

        {/* Right: Order Book */}
        <div className="hx-flex-[3] hx-overflow-y-auto">
          <OrderBook pair={pair} />
        </div>
      </div>

      {/* Bottom: Trades tape + Order form */}
      <div className="hx-flex hx-border-t hx-border-gray-200 dark:hx-border-gray-800" style={{ height: 250 }}>
        <div className="hx-flex-[6] hx-border-r hx-border-gray-200 dark:hx-border-gray-800 hx-overflow-hidden">
          <TradesTape pair={pair} />
        </div>
        <div className="hx-flex-[4] hx-overflow-y-auto hx-p-3">
          <OrderForm pair={pair} />
        </div>
      </div>
    </div>
  );
}
