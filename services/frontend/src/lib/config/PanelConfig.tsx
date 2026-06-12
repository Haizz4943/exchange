'use client';

import React, { createContext, useContext } from 'react';
import { PanelConfig } from './types';

const DEFAULT_SUPPORTED_PAIRS = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT'];

const PanelConfigContext = createContext<PanelConfig | null>(null);

export function PanelConfigProvider({
  value,
  children,
}: {
  value: PanelConfig;
  children: React.ReactNode;
}) {
  const config: PanelConfig = {
    defaultPair: 'BTCUSDT',
    supportedPairs: DEFAULT_SUPPORTED_PAIRS,
    ...value,
  };
  return <PanelConfigContext.Provider value={config}>{children}</PanelConfigContext.Provider>;
}

export function usePanelConfig(): PanelConfig {
  const ctx = useContext(PanelConfigContext);
  if (!ctx) throw new Error('usePanelConfig must be used inside PanelConfigProvider');
  return ctx;
}
