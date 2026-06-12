'use client';

import { TradeScreen } from '@/features/trade/components/TradeScreen';

interface TradePairPageProps {
  params: { pair: string };
}

export default function TradePairPage({ params }: TradePairPageProps) {
  return <TradeScreen pair={params.pair.toUpperCase()} />;
}
