import { redirect } from 'next/navigation';

export default function TradePage() {
  const defaultPair = process.env.NEXT_PUBLIC_DEFAULT_PAIR ?? 'BTCUSDT';
  redirect(`/trade/${defaultPair}`);
}
