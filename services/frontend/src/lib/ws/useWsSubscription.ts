'use client';

import { useEffect } from 'react';
import { useWsClient } from './WsProvider';

/**
 * Declaratively subscribe to a WS channel.
 * Ref-counted: multiple components can subscribe to the same channel;
 * the actual SUBSCRIBE/UNSUBSCRIBE is sent once.
 */
export function useWsSubscription(channel: string | null) {
  const ws = useWsClient();
  useEffect(() => {
    if (!channel) return;
    const unsub = ws.subscribe(channel);
    return unsub;
  }, [ws, channel]);
}
