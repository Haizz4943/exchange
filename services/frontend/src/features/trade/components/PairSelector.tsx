'use client';

import React from 'react';
import { usePanelConfig } from '@/lib/config/PanelConfig';
import { useTradeStore } from '@/features/trade/store';
import { useNavigation } from '@/lib/navigation/useNavigation';
import { Select } from '@/components/ui/Select';

interface PairSelectorProps {
  currentPair: string;
}

export function PairSelector({ currentPair }: PairSelectorProps) {
  const { supportedPairs = [] } = usePanelConfig();
  const setCurrentPair = useTradeStore((s) => s.setCurrentPair);
  const { navigate } = useNavigation();

  const options = supportedPairs.map((p) => ({ value: p, label: p }));

  const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const pair = e.target.value;
    setCurrentPair(pair);
    navigate({ screen: 'trade', pair });
  };

  return (
    <Select
      aria-label="Select trading pair"
      options={options}
      value={currentPair}
      onChange={handleChange}
      className="hx-w-40"
    />
  );
}
