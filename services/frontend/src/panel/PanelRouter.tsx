'use client';

import React, { useState } from 'react';
import { PanelRoute } from '@/lib/config/types';
import { PanelNavigationContext } from '@/lib/navigation/useNavigation';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { TradeScreen } from '@/features/trade/components/TradeScreen';
import { WalletOverview } from '@/features/wallet/components/WalletOverview';
import { OpenOrdersTable } from '@/features/orders/components/OpenOrdersTable';
import { TradeHistoryTable } from '@/features/trades/components/TradeHistoryTable';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { DepositDialog } from '@/features/wallet/components/DepositDialog';

export function PanelRouter({ initialRoute }: { initialRoute?: PanelRoute }) {
  const { isAuthed } = useAuthContext();
  const [route, setRoute] = useState<PanelRoute>(
    initialRoute ?? { screen: 'trade', pair: 'BTCUSDT' },
  );

  const effectiveRoute: PanelRoute = isAuthed ? route : { screen: 'login' };

  return (
    <PanelNavigationContext.Provider value={{ route: effectiveRoute, navigate: setRoute }}>
      {effectiveRoute.screen === 'login' && <LoginForm />}
      {effectiveRoute.screen === 'trade' && <TradeScreen pair={effectiveRoute.pair} />}
      {effectiveRoute.screen === 'wallet' && <WalletOverview />}
      {effectiveRoute.screen === 'orders' && <OpenOrdersTable />}
      {effectiveRoute.screen === 'trades' && <TradeHistoryTable />}
      {effectiveRoute.screen === 'deposit' && <DepositDialog />}
    </PanelNavigationContext.Provider>
  );
}
