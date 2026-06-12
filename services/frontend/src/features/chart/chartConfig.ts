import type { Resolution } from '@/lib/api/endpoints/marketData';

/** Default time range per resolution (in seconds) */
export function defaultRange(resolution: Resolution): { from: number; to: number } {
  const to = Math.floor(Date.now() / 1000);
  const barCount = 300;
  const intervalSeconds: Record<Resolution, number> = {
    '1m': 60,
    '5m': 300,
    '15m': 900,
    '1h': 3_600,
    '4h': 14_400,
    '1d': 86_400,
  };
  const from = to - barCount * intervalSeconds[resolution];
  return { from, to };
}

/** Candlestick series color options */
export const seriesOptions = {
  upColor: '#26a69a',
  downColor: '#ef5350',
  borderVisible: false,
  wickUpColor: '#26a69a',
  wickDownColor: '#ef5350',
} as const;

/** Chart layout options (dark-friendly defaults) */
export const chartOptions = {
  layout: {
    background: { color: 'transparent' },
    textColor: '#999999',
  },
  grid: {
    vertLines: { color: '#2a2a2a' },
    horzLines: { color: '#2a2a2a' },
  },
  crosshair: {
    mode: 1, // CrosshairMode.Magnet
  },
  rightPriceScale: {
    borderVisible: false,
  },
  timeScale: {
    borderVisible: false,
    timeVisible: true,
    secondsVisible: false,
  },
} as const;
