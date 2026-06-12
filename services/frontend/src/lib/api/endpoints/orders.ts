import { ApiClient } from '../client';
import { PageResponse } from './wallets';

export interface PlaceOrderRequest {
  client_order_id?: string;
  pair: string;
  side: 'BUY' | 'SELL';
  type: 'MARKET' | 'LIMIT';
  quantity: string;
  limit_price?: string;
  time_in_force?: 'GTC';
}

export interface OrderResponse {
  order_id: string;
  client_order_id: string | null;
  pair: string;
  side: 'BUY' | 'SELL';
  type: 'MARKET' | 'LIMIT';
  quantity: string;
  limit_price: string | null;
  time_in_force: string;
  state: OrderState;
  filled_qty: string;
  avg_fill_price: string | null;
  freeze_amount: string;
  freeze_asset: string;
  created_at: string;
  updated_at?: string;
}

export type OrderState =
  | 'NEW'
  | 'OPEN'
  | 'PARTIALLY_FILLED'
  | 'FILLED'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'REJECTED';

export interface ListOrdersParams {
  pair?: string;
  state?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
  [key: string]: string | number | undefined;
}

export interface TradingPair {
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
  tickSize: string;
  stepSize: string;
  minNotional: string;
  enabled: boolean;
}

export function ordersApi(client: ApiClient) {
  return {
    place: (req: PlaceOrderRequest) =>
      client.post<OrderResponse>('/api/v1/orders', req),

    cancel: (orderId: string) =>
      client.delete<{ order_id: string; state: string }>(`/api/v1/orders/${orderId}`),

    get: (orderId: string) =>
      client.get<OrderResponse>(`/api/v1/orders/${orderId}`),

    list: (params?: ListOrdersParams) =>
      client.get<PageResponse<OrderResponse>>('/api/v1/orders', params),

    getTradingPairs: () =>
      client.get<TradingPair[]>('/api/v1/trading-pairs'),
  };
}
