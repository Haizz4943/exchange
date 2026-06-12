'use client';

import React, { useEffect, useRef } from 'react';
import type { IChartApi, ISeriesApi, CandlestickData, Time } from 'lightweight-charts';
import type { Resolution } from '@/lib/api/endpoints/marketData';
import { useChartData, klineToBar } from './useChartData';
import { chartOptions, seriesOptions } from './chartConfig';
import { useWsClient } from '@/lib/ws/WsProvider';
import { Skeleton } from '@/components/ui/Skeleton';

export interface CandlestickChartProps {
  pair: string;
  resolution?: Resolution;
  height?: number;
}

function ChartSkeleton({ height }: { height: number }) {
  return (
    <div className="hx-absolute hx-inset-0 hx-flex hx-flex-col hx-gap-2 hx-p-4">
      <Skeleton height={height * 0.6} className="hx-w-full" />
      <Skeleton height={height * 0.15} className="hx-w-full" />
    </div>
  );
}

export function CandlestickChart({
  pair,
  resolution = '1h',
  height = 500,
}: CandlestickChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  // Use 'any' for the series ref to be compatible with both v4 and v5 API
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | any>(null);
  const ws = useWsClient();

  const { data: history, isLoading } = useChartData(pair, resolution);

  // 1. Create/destroy chart on mount/unmount
  useEffect(() => {
    if (!containerRef.current) return;

    // Dynamic import to ensure SSR safety (window access)
    let chart: IChartApi;

    import('lightweight-charts').then(({ createChart }) => {
      if (!containerRef.current) return;
      chart = createChart(containerRef.current, {
        ...chartOptions,
        width: containerRef.current.clientWidth,
        height,
      });

      // lightweight-charts v5: addCandlestickSeries still exists on IChartApi
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const series = (chart as any).addCandlestickSeries
        ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (chart as any).addCandlestickSeries(seriesOptions)
        : // v5 new API fallback
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (chart as any).addSeries({ type: 'Candlestick', ...seriesOptions });

      chartRef.current = chart;
      seriesRef.current = series;
    });

    const onResize = () => {
      if (containerRef.current && chartRef.current) {
        chartRef.current.applyOptions({ width: containerRef.current.clientWidth });
      }
    };
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chartRef.current?.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Only once — we reuse the chart instance

  // 2. Set data when history arrives OR pair/resolution changes
  useEffect(() => {
    if (!seriesRef.current || !history) return;
    // lightweight-charts requires bars sorted ascending by time
    const sorted = [...history]
      .sort((a, b) => (a.time as number) - (b.time as number))
      .map((bar) => ({ ...bar, time: bar.time as Time }));
    seriesRef.current.setData(sorted as CandlestickData[]);
  }, [history]);

  // 3. Subscribe to live kline updates via WS
  useEffect(() => {
    const channel = `market:${pair}:kline:${resolution}`;
    const unsubChannel = ws.subscribe(channel);
    const unsubHandler = ws.onSchema('market-data.kline.v1', (payload) => {
      if (!seriesRef.current) return;
      const msg = payload as {
        pair: string;
        interval: string;
        time: number;
        open: string;
        high: string;
        low: string;
        close: string;
      };
      if (msg.pair !== pair || msg.interval !== resolution) return;
      const bar = klineToBar(msg);
      seriesRef.current.update({ ...bar, time: bar.time as Time });
    });
    return () => {
      unsubChannel();
      unsubHandler();
    };
  }, [ws, pair, resolution]);

  return (
    <div className="hx-relative" style={{ height }}>
      {isLoading && <ChartSkeleton height={height} />}
      <div
        ref={containerRef}
        className="hx-w-full hx-h-full"
        aria-label={`Candlestick chart for ${pair} at ${resolution} resolution`}
        role="img"
      />
    </div>
  );
}
