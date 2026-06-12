import { ApiClient } from '../client';
import { PageResponse } from './wallets';

export interface TradeRecord {
  tradeId: string;
  orderId: string;
  pair: string;
  side: 'BUY' | 'SELL';
  price: string;
  quantity: string;
  quoteQuantity: string;
  fee: string;
  feeAsset: string;
  role: 'TAKER' | 'MAKER';
  executedAt: string;
}

export interface ListTradesParams {
  pair?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  [key: string]: string | number | undefined;
}

export function tradesApi(client: ApiClient) {
  return {
    list: (params?: ListTradesParams) =>
      client.get<PageResponse<TradeRecord>>('/api/v1/trades', params),
  };
}
