'use client';

import { createContext, useContext } from 'react';
import { useRouter } from 'next/navigation';
import { PanelRoute } from '@/lib/config/types';

function panelRouteToUrl(route: PanelRoute): string {
  switch (route.screen) {
    case 'login':
      return '/login';
    case 'trade':
      return `/trade/${route.pair}`;
    case 'wallet':
      return '/wallet';
    case 'orders':
      return '/orders';
    case 'trades':
      return '/trades';
    case 'deposit':
      return '/deposit';
    default:
      return '/';
  }
}

export interface PanelNavigationContextValue {
  route: PanelRoute;
  navigate: (route: PanelRoute) => void;
}

export const PanelNavigationContext = createContext<PanelNavigationContextValue | null>(null);

/**
 * useNavigation — works in both embedded and standalone modes.
 * In embedded: uses PanelNavigationContext (internal state-based routing).
 * In standalone: uses Next.js useRouter (URL-based routing).
 *
 * Note: useRouter is always called unconditionally (rules of hooks compliance).
 * When in embedded mode, panelCtx takes precedence and the router is unused.
 */
export function useNavigation() {
  const panelCtx = useContext(PanelNavigationContext);
  // Always call — this is a Next.js App Router hook, safe in client components.
  const nextRouter = useRouter();

  return {
    navigate: (route: PanelRoute) => {
      if (panelCtx) {
        panelCtx.navigate(route);
      } else {
        nextRouter.push(panelRouteToUrl(route));
      }
    },
    currentRoute: panelCtx?.route ?? null,
  };
}
