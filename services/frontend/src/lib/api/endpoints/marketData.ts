import { ApiClient } from '../client';

export type Resolution = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';

/** Map internal resolution to UDF resolution string */
export function toUdfResolution(r: Resolution): string {
  const map: Record<Resolution, string> = {
    '1m': '1',
    '5m': '5',
    '15m': '15',
    '1h': '60',
    '4h': '240',
    '1d': '1D',
  };
  return map[r];
}

export interface UdfHistoryResponse {
  s: 'ok' | 'no_data' | 'error';
  t?: number[];
  o?: string[];
  h?: string[];
  l?: string[];
  c?: string[];
  v?: string[];
  nextTime?: number;
  errmsg?: string;
}

export interface OrderBookResponse {
  pair: string;
  bids: [string, string][];
  asks: [string, string][];
  updated_at: string;
  stale: boolean;
}

export interface TickerResponse {
  pair: string;
  best_bid: string;
  best_ask: string;
  last_price: string;
  updated_at: string;
}

export interface ExchangeInfoPair {
  symbol: string;
  base_asset: string;
  quote_asset: string;
  tick_size: string;
  step_size: string;
  status: string;
}

export interface ExchangeInfoResponse {
  pairs: ExchangeInfoPair[];
  updated_at: string;
}

export function marketDataApi(client: ApiClient) {
  return {
    /** Fetch OHLCV history via UDF endpoint */
    getHistory: (
      symbol: string,
      resolution: string,
      from: number,
      to: number,
      countback?: number,
    ) =>
      client.get<UdfHistoryResponse>('/udf/history', {
        symbol,
        resolution,
        from,
        to,
        ...(countback !== undefined ? { countback } : {}),
      }),

    getOrderBook: (pair: string, depth = 20) =>
      client.get<OrderBookResponse>(`/api/v1/marketdata/orderbook/${pair}`, { depth }),

    getTicker: (pair: string) =>
      client.get<TickerResponse>(`/api/v1/marketdata/ticker/${pair}`),

    getExchangeInfo: () =>
      client.get<ExchangeInfoResponse>('/api/v1/marketdata/exchangeInfo'),
  };
}
