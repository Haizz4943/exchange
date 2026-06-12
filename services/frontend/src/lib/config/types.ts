import type { ErrorInfo } from 'react';

export type PanelRoute =
  | { screen: 'login' }
  | { screen: 'trade'; pair: string }
  | { screen: 'wallet' }
  | { screen: 'orders' }
  | { screen: 'trades' }
  | { screen: 'deposit' };

export type HaizzEvent =
  | { type: 'auth.expired' }
  | { type: 'navigate'; route: PanelRoute }
  | { type: 'order.placed'; orderId: string; pair: string }
  | { type: 'order.cancelled'; orderId: string }
  | { type: 'error'; error: Error; info?: ErrorInfo };

export interface PanelConfig {
  mode: 'standalone' | 'embedded';
  gatewayBaseUrl: string;
  locale?: string;
  theme?: 'light' | 'dark' | 'inherit';
  defaultPair?: string;
  supportedPairs?: string[];
  onEvent?: (event: HaizzEvent) => void;
}
