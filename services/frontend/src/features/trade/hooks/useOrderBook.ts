'use client';

import { useTradeStore } from '../store';

export function useOrderBook(pair: string) {
  const depth = useTradeStore((s) => s.depth[pair]);
  return { depth, isEmpty: !depth };
}
