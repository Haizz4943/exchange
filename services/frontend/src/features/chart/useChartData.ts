'use client';

import { useQuery } from '@tanstack/react-query';
import { usePanelConfig } from '@/lib/config/PanelConfig';
import { Resolution, toUdfResolution } from '@/lib/api/endpoints/marketData';
import { defaultRange } from './chartConfig';

export interface Bar {
  time: number; // Unix seconds UTC
  open: number;
  high: number;
  low: number;
  close: number;
}

async function fetchHistory(
  gatewayBaseUrl: string,
  pair: string,
  resolution: Resolution,
  range: { from: number; to: number },
): Promise<Bar[]> {
  const udfRes = toUdfResolution(resolution);
  const url = new URL(`${gatewayBaseUrl}/udf/history`);
  url.searchParams.set('symbol', pair);
  url.searchParams.set('resolution', udfRes);
  url.searchParams.set('from', String(range.from));
  url.searchParams.set('to', String(range.to));
  url.searchParams.set('countback', '300');

  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`UDF history fetch failed: ${res.status}`);

  const data = await res.json();
  if (data.s !== 'ok' || !data.t) return [];

  // Map compact string arrays to typed Bar objects.
  // o/h/l/c are strings per spec §7.3 — parse once here.
  return (data.t as number[]).map((time: number, i: number) => ({
    time,
    open: parseFloat(data.o[i]),
    high: parseFloat(data.h[i]),
    low: parseFloat(data.l[i]),
    close: parseFloat(data.c[i]),
  }));
}

export function useChartData(pair: string, resolution: Resolution) {
  const { gatewayBaseUrl } = usePanelConfig();
  const range = defaultRange(resolution);

  return useQuery({
    queryKey: ['udf-history', { pair, resolution }] as const,
    queryFn: () => fetchHistory(gatewayBaseUrl, pair, resolution, range),
    staleTime: 60_000,
    retry: 1,
  });
}

/** Convert a WS kline payload to a Bar for series.update() */
export function klineToBar(msg: {
  open: string | number;
  high: string | number;
  low: string | number;
  close: string | number;
  time: number;
}): Bar {
  return {
    time: msg.time,
    open: typeof msg.open === 'string' ? parseFloat(msg.open) : msg.open,
    high: typeof msg.high === 'string' ? parseFloat(msg.high) : msg.high,
    low: typeof msg.low === 'string' ? parseFloat(msg.low) : msg.low,
    close: typeof msg.close === 'string' ? parseFloat(msg.close) : msg.close,
  };
}
