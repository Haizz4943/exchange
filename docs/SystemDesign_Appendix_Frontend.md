# System Design Appendix — Frontend (Next.js Trading Panel)

**Parent Document:** `SystemDesign.md` v1.0
**Module:** `haizz-trading-panel` (npm package name: `@haizz/trading-panel`)
**Port (standalone dev):** 3000
**Deployment Targets:** Stage 1 — standalone Next.js app; Stage 2 — federated module embedded in host Next.js app
**Related Master Sections:** `SystemDesign.md` §4.7, §7.5, §9 (Frontend Architecture)
**Status:** Ready for implementation

---

## Table of Contents

1. [Scope & Design Goals](#1-scope--design-goals)
2. [Project Structure](#2-project-structure)
3. [Dual-Mode Entry Points](#3-dual-mode-entry-points)
4. [Component Hierarchy](#4-component-hierarchy)
5. [State Management](#5-state-management)
6. [API Client & WebSocket Layer](#6-api-client--websocket-layer)
7. [TradingView Chart Wrapper](#7-tradingview-chart-wrapper)
8. [Authentication Flows (Both Modes)](#8-authentication-flows-both-modes)
9. [Routing Strategy](#9-routing-strategy)
10. [Styling & Theming](#10-styling--theming)
11. [Build, Bundle & Publish](#11-build-bundle--publish)
12. [Configuration & Environment](#12-configuration--environment)
13. [Error Handling & UX](#13-error-handling--ux)
14. [Testing Strategy](#14-testing-strategy)
15. [Open Implementation Notes](#15-open-implementation-notes)

---

## 1. Scope & Design Goals

This appendix specifies the **implementation-level design** for the Next.js frontend. It assumes familiarity with `SystemDesign.md` §9 (the architectural decisions — dual-mode, module federation pattern, prop-based mode selection).

### 1.1 Design Goals (in priority order)

1. **One codebase, two targets.** Stage 1 standalone and Stage 2 embedded share every component, store, and service. Mode is a runtime prop, not a build-time flag.
2. **SSR-safe boundary.** In Stage 2, the host renders first; our module lazy-loads client-side. No `window`/`document` access during module evaluation.
3. **Zero host pollution.** No global CSS, no global JS singletons, no mutations to `document.body`. Host app's styles and state remain isolated.
4. **Predictable real-time updates.** A single WebSocket per session multiplexes all subscriptions. Components subscribe/unsubscribe declaratively via hooks.
5. **TradingView Lightweight faithful.** Chart renders <500 ms after history fetch; live updates applied within 200 ms of WS push.
6. **Accessible & responsive.** Works on 1280×720 desktop; reflows gracefully to 1024 wide. Mobile out of scope.

### 1.2 What's Explicitly Out of Scope

- **Mobile native app** — responsive web only.
- **TradingView Advanced Charting Library** — Lightweight only in MVP. Advanced Charting migration path noted in §7.5.
- **Offline mode** — requires live connection; graceful degradation only.
- **PWA features** — post-MVP.
- **i18n beyond English scaffolding** — English-only copy, but `locale` prop wired for future.

### 1.3 Key Architectural Distinctions

- **Not an SPA in the traditional sense** — Next.js App Router with client components for interactive screens. SSR enabled for marketing/public pages (Stage 1 only); embedded mode is pure CSR.
- **No server actions.** All data fetching goes through REST/WSS to the Gateway. Keeps the FE transportable to other hosts.
- **No BFF layer.** The Gateway is the only backend surface. This is deliberate — embedding an FE that requires its own BFF would complicate Stage 2.

---

## 2. Project Structure

### 2.1 Top-Level Layout

```
haizz-trading-panel/
├── package.json
├── next.config.js                  # standalone-mode Next config
├── rollup.config.mjs               # library-mode bundle config (for @haizz/trading-panel npm)
├── tsconfig.json
├── tailwind.config.js
├── postcss.config.js
├── .env.example
├── public/                          # standalone assets only — not shipped in npm
│   └── favicon.ico
├── src/
│   ├── app/                         # Next.js App Router — STANDALONE ONLY
│   │   ├── layout.tsx
│   │   ├── page.tsx                # landing / login redirect
│   │   ├── (auth)/
│   │   │   ├── login/page.tsx
│   │   │   └── register/page.tsx
│   │   ├── (trader)/
│   │   │   ├── layout.tsx           # authenticated layout shell
│   │   │   ├── trade/page.tsx
│   │   │   ├── trade/[pair]/page.tsx
│   │   │   ├── wallet/page.tsx
│   │   │   ├── orders/page.tsx
│   │   │   ├── trades/page.tsx
│   │   │   └── deposit/page.tsx
│   │   └── providers.tsx            # React Query, Zustand hydrator
│   │
│   ├── panel/                       # THE embeddable root — used by BOTH modes
│   │   ├── HaizzTradingPanel.tsx    # top-level component, props-driven mode
│   │   ├── PanelRouter.tsx          # internal route state (when embedded)
│   │   └── PanelProviders.tsx       # providers scoped inside the panel
│   │
│   ├── features/                    # feature modules — composed by panel/app
│   │   ├── auth/
│   │   │   ├── components/
│   │   │   │   ├── LoginForm.tsx
│   │   │   │   └── RegisterForm.tsx
│   │   │   ├── hooks/
│   │   │   │   ├── useLogin.ts
│   │   │   │   └── useLogout.ts
│   │   │   └── store.ts
│   │   ├── trade/
│   │   │   ├── components/
│   │   │   │   ├── TradeScreen.tsx       # orchestrates chart + book + form
│   │   │   │   ├── OrderForm.tsx
│   │   │   │   ├── OrderBook.tsx
│   │   │   │   ├── TradesTape.tsx
│   │   │   │   └── PairSelector.tsx
│   │   │   ├── hooks/
│   │   │   │   ├── usePlaceOrder.ts
│   │   │   │   └── useOrderBook.ts
│   │   │   └── store.ts
│   │   ├── wallet/
│   │   │   ├── components/
│   │   │   │   ├── WalletOverview.tsx
│   │   │   │   ├── DepositDialog.tsx
│   │   │   │   └── WithdrawDialog.tsx
│   │   │   ├── hooks/
│   │   │   └── store.ts
│   │   ├── orders/
│   │   │   ├── components/
│   │   │   │   ├── OpenOrdersTable.tsx
│   │   │   │   └── OrderHistoryTable.tsx
│   │   │   ├── hooks/
│   │   │   └── store.ts
│   │   ├── chart/                          # TradingView Lightweight wrapper
│   │   │   ├── CandlestickChart.tsx
│   │   │   ├── useChartData.ts
│   │   │   └── chartConfig.ts
│   │   └── trades/
│   │       ├── components/
│   │       │   └── TradeHistoryTable.tsx
│   │       └── hooks/
│   │
│   ├── lib/                          # shared infrastructure
│   │   ├── api/
│   │   │   ├── client.ts             # base fetch wrapper
│   │   │   ├── endpoints/            # typed endpoint groups
│   │   │   │   ├── auth.ts
│   │   │   │   ├── wallets.ts
│   │   │   │   ├── orders.ts
│   │   │   │   ├── trades.ts
│   │   │   │   └── marketData.ts
│   │   │   └── errors.ts             # API error normalization
│   │   ├── ws/
│   │   │   ├── WsClient.ts            # WebSocket manager — see §6
│   │   │   ├── useWsSubscription.ts   # React hook
│   │   │   └── WsProvider.tsx
│   │   ├── auth/
│   │   │   ├── TokenStore.ts          # in-memory access token + refresh
│   │   │   ├── refreshFlow.ts
│   │   │   └── AuthBridge.ts          # host-provided auth adapter (embedded mode)
│   │   ├── config/
│   │   │   ├── PanelConfig.tsx        # React context for runtime config
│   │   │   └── types.ts
│   │   └── utils/
│   │       ├── bigNumber.ts           # decimal formatting
│   │       ├── correlationId.ts
│   │       └── format.ts
│   │
│   ├── components/                    # presentational, feature-agnostic
│   │   ├── ui/
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   ├── Select.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── Table.tsx
│   │   │   ├── Skeleton.tsx
│   │   │   └── Toast.tsx
│   │   └── layout/
│   │       ├── Header.tsx
│   │       ├── Sidebar.tsx
│   │       └── ErrorBoundary.tsx
│   │
│   └── styles/
│       ├── globals.css                # standalone only
│       └── panel.css                  # scoped to .haizz-panel wrapper
│
└── tests/                              # see §14
    ├── unit/
    ├── integration/
    └── e2e/
```

### 2.2 Source Boundary: `app/` vs `panel/`

Critical conceptual split:

| Directory | Used by | Contains |
|-----------|---------|----------|
| `src/app/` | Stage 1 standalone Next.js only | App Router pages, layouts, route-level server components |
| `src/panel/` | Both modes | The embeddable root component and internal "router" |
| `src/features/` | Both modes | All feature UI and business logic — the **shared core** |
| `src/lib/` | Both modes | Infra (API client, WS, auth, utils) |
| `src/components/` | Both modes | Presentational UI primitives |

**Rule:** `features/`, `lib/`, `components/` never import from `app/`. The `app/` directory is standalone-only scaffolding — it composes `features/` and `panel/`, but nothing imports back into it.

### 2.3 Import Direction Rules (enforced via ESLint)

```
app/   ─────┐       (standalone-only)
panel/ ─────┤  ──►  features/  ──►  lib/
            │           │            │
            │           ▼            ▼
            └──►    components/   shared/utils
```

- `panel/` can import `features/`, `lib/`, `components/`.
- `features/` can import `lib/`, `components/`, other `features/` (minimize).
- `lib/` self-contained; can import `components/ui/` only for user-facing error displays.
- `components/` pure — no feature imports, no API imports.

ESLint rule using `eslint-plugin-import` `no-restricted-paths`:

```js
rules: {
  'import/no-restricted-paths': ['error', {
    zones: [
      { target: 'src/features', from: 'src/app' },
      { target: 'src/features', from: 'src/panel' },
      { target: 'src/lib', from: 'src/features' },
      { target: 'src/lib', from: 'src/panel' },
      { target: 'src/components', from: 'src/features' },
    ],
  }],
}
```

---

## 3. Dual-Mode Entry Points

### 3.1 Standalone Entry (Stage 1)

`src/app/layout.tsx`:

```tsx
import { Providers } from './providers';
import './globals.css';

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
```

`src/app/providers.tsx`:

```tsx
'use client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PanelConfigProvider } from '@/lib/config/PanelConfig';
import { AuthStandaloneProvider } from '@/lib/auth/AuthStandaloneProvider';
import { WsProvider } from '@/lib/ws/WsProvider';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
});

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <PanelConfigProvider value={{ mode: 'standalone', gatewayBaseUrl: process.env.NEXT_PUBLIC_GATEWAY_URL! }}>
      <QueryClientProvider client={queryClient}>
        <AuthStandaloneProvider>
          <WsProvider>{children}</WsProvider>
        </AuthStandaloneProvider>
      </QueryClientProvider>
    </PanelConfigProvider>
  );
}
```

Each route under `src/app/(trader)/` is a thin wrapper that renders a feature component:

```tsx
// src/app/(trader)/trade/[pair]/page.tsx
'use client';
import { TradeScreen } from '@/features/trade/components/TradeScreen';

export default function TradePairPage({ params }: { params: { pair: string } }) {
  return <TradeScreen pair={params.pair.toUpperCase()} />;
}
```

### 3.2 Embedded Entry (Stage 2)

`src/panel/HaizzTradingPanel.tsx` — the default export of the npm package:

```tsx
'use client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PanelConfigProvider } from '@/lib/config/PanelConfig';
import { AuthBridgeProvider } from '@/lib/auth/AuthBridge';
import { WsProvider } from '@/lib/ws/WsProvider';
import { PanelRouter } from './PanelRouter';
import './panel.css';

export interface HaizzTradingPanelProps {
  mode: 'standalone' | 'embedded';
  auth?: {
    accessToken: string;
    refreshCallback?: () => Promise<string>;
    onAuthExpired?: () => void;
  };
  gatewayBaseUrl?: string;
  theme?: 'light' | 'dark' | 'inherit';
  locale?: string;
  initialRoute?: PanelRoute;         // { screen: 'trade', pair: 'BTCUSDT' }
  onEvent?: (event: HaizzEvent) => void;
}

export function HaizzTradingPanel(props: HaizzTradingPanelProps) {
  // Create a fresh QueryClient per mount — each embed is independent.
  const queryClient = useMemo(() => new QueryClient(queryClientOptions), []);

  const gatewayBaseUrl =
    props.gatewayBaseUrl ??
    (props.mode === 'standalone' ? process.env.NEXT_PUBLIC_GATEWAY_URL : undefined);

  if (!gatewayBaseUrl) {
    return <PanelError message="Missing gatewayBaseUrl prop (required in embedded mode)" />;
  }

  return (
    <div className="haizz-panel" data-theme={props.theme ?? 'light'} data-mode={props.mode}>
      <PanelConfigProvider value={{ mode: props.mode, gatewayBaseUrl, locale: props.locale, onEvent: props.onEvent }}>
        <QueryClientProvider client={queryClient}>
          <AuthBridgeProvider auth={props.auth} mode={props.mode}>
            <WsProvider>
              <PanelRouter initialRoute={props.initialRoute} />
            </WsProvider>
          </AuthBridgeProvider>
        </QueryClientProvider>
      </PanelConfigProvider>
    </div>
  );
}

export default HaizzTradingPanel;
```

### 3.3 Shared Root vs Separate Roots — Why This Structure

A naive alternative: have `src/app/` import from `panel/HaizzTradingPanel` and reuse it. The chosen structure is subtly different — `app/` is a fully independent shell that composes `features/` directly, same as `panel/`. Reasons:

1. **Next.js App Router server components** can live in `app/` but NOT inside the npm-shipped panel module. Separating them prevents accidental server-only imports leaking into the library bundle.
2. **Routing models differ.** Standalone uses Next.js routing (URL → page); embedded uses internal panel state (no URL changes, host owns URL). Forcing one model on both creates awkward abstractions.
3. **Providers compose differently.** Standalone has a single global `QueryClient`; embedded creates a per-mount `QueryClient` so multiple embeds don't share cache.

### 3.4 `PanelRouter` — Internal Navigation (Embedded Mode)

```tsx
// src/panel/PanelRouter.tsx
type PanelRoute =
  | { screen: 'login' }
  | { screen: 'trade'; pair: string }
  | { screen: 'wallet' }
  | { screen: 'orders' }
  | { screen: 'trades' }
  | { screen: 'deposit' };

export function PanelRouter({ initialRoute }: { initialRoute?: PanelRoute }) {
  const [route, setRoute] = useState<PanelRoute>(initialRoute ?? { screen: 'trade', pair: 'BTCUSDT' });
  const { isAuthed } = useAuth();

  // Force login if unauthed
  const effectiveRoute: PanelRoute = isAuthed ? route : { screen: 'login' };

  return (
    <PanelNavigationContext.Provider value={{ route: effectiveRoute, navigate: setRoute }}>
      {effectiveRoute.screen === 'login' && <LoginScreen />}
      {effectiveRoute.screen === 'trade' && <TradeScreen pair={effectiveRoute.pair} />}
      {effectiveRoute.screen === 'wallet' && <WalletOverview />}
      {effectiveRoute.screen === 'orders' && <OrdersScreen />}
      {effectiveRoute.screen === 'trades' && <TradesScreen />}
      {effectiveRoute.screen === 'deposit' && <DepositScreen />}
    </PanelNavigationContext.Provider>
  );
}
```

Internal links (e.g., "View all orders" button) use `useNavigation()` hook to call `navigate()` — not Next.js's `<Link>`. In standalone mode, the same components use Next's `useRouter()` via a `useNavigation` abstraction:

```tsx
// src/lib/navigation/useNavigation.ts
export function useNavigation() {
  const panelCtx = useContext(PanelNavigationContext);    // embedded mode
  const nextRouter = useRouter();                          // standalone mode

  return {
    navigate: (route: PanelRoute) => {
      if (panelCtx) {
        panelCtx.navigate(route);
      } else {
        // translate PanelRoute to Next URL
        nextRouter.push(panelRouteToUrl(route));
      }
    },
  };
}
```

---

## 4. Component Hierarchy

### 4.1 Feature Components (Stable Interface Contracts)

Each feature exports one or more **screen components** (top-level) composed of smaller **presentation components**.

**`TradeScreen`** — the main trading UI.

```tsx
interface TradeScreenProps {
  pair: string;    // e.g., "BTCUSDT"
}
```

Layout:
```
┌─────────────────────────────────────────────────────┐
│ Header: PairSelector · LastPrice · 24hChange        │
├──────────────────────────────┬──────────────────────┤
│                              │                      │
│   CandlestickChart           │   OrderBook          │
│   (70% width)                │   (30% width)        │
│                              │                      │
├──────────────────────────────┼──────────────────────┤
│   TradesTape                 │   OrderForm          │
│   (left half)                │   (right half)       │
└──────────────────────────────┴──────────────────────┘
```

Responsibilities:
- Owns the current `pair` selection, propagates to children.
- Subscribes to `market:<pair>:depth`, `market:<pair>:ticker` channels via WS.
- Fetches user's open orders for the pair (highlighted on chart as price lines).
- Coordinates order placement feedback (toast on success, inline error on failure).

**`OrderForm`** — BUY/SELL order entry.

```tsx
interface OrderFormProps {
  pair: string;
  side?: 'BUY' | 'SELL';       // default BUY
}
```

Responsibilities:
- Bind Zod-validated form state for `type` (MARKET/LIMIT), `side`, `quantity`, `limitPrice`.
- Compute client-side freeze estimate for user visibility.
- Submit via `usePlaceOrder()` mutation.
- Display last known `bestBid`/`bestAsk` as hints.
- Disable submit if user's available balance < estimated freeze.

**`OrderBook`** — depth visualization.

```tsx
interface OrderBookProps {
  pair: string;
  levels?: number;    // default 15
}
```

Responsibilities:
- Consume `market:<pair>:depth` subscription (reads from Zustand store).
- Render bids (green, descending price) and asks (red, ascending price).
- Each row shows `price`, `quantity`, cumulative bar width.
- Click a price → autofill `OrderForm.limitPrice`.

**`CandlestickChart`** — TradingView wrapper. See §7.

**`WalletOverview`**, **`OpenOrdersTable`**, **`OrderHistoryTable`**, **`TradeHistoryTable`** — straightforward table UIs with pagination.

### 4.2 Presentation Primitives (`components/ui/`)

Minimal set:
- `Button` — `variant: primary | secondary | danger | ghost`, sizes, loading state.
- `Input` — with error display, controlled/uncontrolled variants.
- `Select` — native + enhanced (headless pattern for dropdown).
- `Modal` — portal-based, SSR-safe (uses `ReactDOM.createPortal` inside `useEffect`).
- `Table` — header/row/cell; supports sticky header.
- `Skeleton` — loading placeholder rectangles.
- `Toast` — toast stack, portaled; `useToast()` hook pushes messages.

All primitives are **styled with Tailwind utility classes prefixed `hx-`** (see §10). They never touch global state; pure props-driven.

### 4.3 Error Boundary

Top-level `ErrorBoundary` wraps the panel root. On error:
- Standalone: renders a full-page error screen.
- Embedded: renders a contained error box, calls `onEvent({ type: 'error', ... })` so host can react.

```tsx
// Wrapping inside HaizzTradingPanel
<ErrorBoundary onError={(err, info) => config.onEvent?.({ type: 'error', error: err, info })}>
  <PanelRouter />
</ErrorBoundary>
```

---

## 5. State Management

### 5.1 Store Strategy Matrix

| Data Type | Tool | Rationale |
|-----------|------|-----------|
| Server cache (orders, trades, wallets list) | **TanStack Query** | Built-in caching, retry, invalidation, SSR hydration |
| Real-time streams (depth, ticker, user-order updates) | **Zustand** | Lightweight, no reducer boilerplate, selective subscriptions |
| Form state | **React Hook Form** | Zod validation integration, uncontrolled perf |
| UI ephemeral (modal open, active tab, etc.) | `useState` in component | No need for global |
| Auth (access token + user) | **Zustand (dedicated `authStore`)** | Accessed from anywhere; separated from query cache |
| Panel config (mode, gateway URL, locale) | **React Context** | Read-only after mount; no updates |

### 5.2 Zustand Store Design

Each feature owns one slice. Stores are **instance-scoped per mount** (not singletons) via factory pattern — critical for multiple embedded panel instances:

```ts
// src/features/trade/store.ts
import { create } from 'zustand';

export interface TradeState {
  currentPair: string;
  depth: Record<string, DepthSnapshot>;        // keyed by pair
  ticker: Record<string, Ticker>;
  recentTrades: Record<string, Trade[]>;

  setCurrentPair: (pair: string) => void;
  applyDepthUpdate: (pair: string, depth: DepthSnapshot) => void;
  applyTickerUpdate: (pair: string, ticker: Ticker) => void;
  appendRecentTrade: (pair: string, trade: Trade) => void;
}

export const createTradeStore = () => create<TradeState>((set) => ({
  currentPair: 'BTCUSDT',
  depth: {},
  ticker: {},
  recentTrades: {},

  setCurrentPair: (pair) => set({ currentPair: pair }),
  applyDepthUpdate: (pair, depth) =>
    set((s) => ({ depth: { ...s.depth, [pair]: depth } })),
  applyTickerUpdate: (pair, ticker) =>
    set((s) => ({ ticker: { ...s.ticker, [pair]: ticker } })),
  appendRecentTrade: (pair, trade) =>
    set((s) => ({
      recentTrades: {
        ...s.recentTrades,
        [pair]: [trade, ...(s.recentTrades[pair] ?? [])].slice(0, 100),
      },
    })),
}));
```

Store instances provided via context:

```tsx
// src/panel/PanelProviders.tsx
const TradeStoreContext = createContext<ReturnType<typeof createTradeStore> | null>(null);

export function TradeStoreProvider({ children }: { children: React.ReactNode }) {
  const [store] = useState(() => createTradeStore());
  return <TradeStoreContext.Provider value={store}>{children}</TradeStoreContext.Provider>;
}

export function useTradeStore<T>(selector: (s: TradeState) => T): T {
  const store = useContext(TradeStoreContext);
  if (!store) throw new Error('useTradeStore must be inside TradeStoreProvider');
  return store(selector);
}
```

**Why instance-scoped?** Two `HaizzTradingPanel` mounted in the same host page must not share state. A global Zustand store would leak one panel's subscriptions to the other.

### 5.3 Store List

| Store | Scope | Contents |
|-------|-------|----------|
| `authStore` | Panel-wide | `user`, `isAuthed`, `accessToken` (embedded only — standalone uses cookies) |
| `tradeStore` | Panel-wide | Current pair, live depth/ticker/trades cache per pair |
| `ordersStore` | Panel-wide | User's open orders (real-time updated from WS) |
| `walletStore` | Panel-wide | User's wallet balances (real-time updated from WS) |
| `wsStore` | Panel-wide | WS connection status, active subscriptions set |

Each accessed via a hook: `useAuthStore`, `useTradeStore`, etc.

### 5.4 TanStack Query Usage

Used for:
- `GET /orders` history (paginated, cached 30s)
- `GET /trades` history (cached 30s)
- `GET /wallets/me` initial fetch (then updated by WS)
- `GET /udf/history` (cached per `{pair, resolution, from, to}` key)

Mutations: `POST /orders`, `DELETE /orders/{id}`, `POST /deposits`, `POST /withdrawals`.

Query key conventions:
```ts
['orders', 'list', { pair, state, page, size }]
['trades', 'list', { pair, from, to, page, size }]
['wallets', 'me']
['udf-history', { pair, resolution, from, to }]
```

Invalidation after mutations:
- Place order → invalidate `['orders', 'list']` (all filters).
- Cancel order → invalidate specific order query.
- Deposit/withdraw → invalidate `['wallets', 'me']` and `['wallet-transactions', ...]`.

### 5.5 WS → Store Sync

A single `WsStoreSyncer` component mounts near the panel root. On every WS message matching a known schema, it dispatches to the appropriate Zustand store:

```tsx
// src/lib/ws/WsStoreSyncer.tsx
export function WsStoreSyncer() {
  const ws = useWsClient();
  const tradeStore = useTradeStoreRaw();
  const ordersStore = useOrdersStoreRaw();
  const walletStore = useWalletStoreRaw();

  useEffect(() => {
    const subs = [
      ws.onMessage('market-data.depth.v1', (msg) =>
        tradeStore.getState().applyDepthUpdate(msg.pair, msg)
      ),
      ws.onMessage('market-data.events.v1.ExternalTradeObserved', (msg) =>
        tradeStore.getState().appendRecentTrade(msg.pair, msg)
      ),
      ws.onMessage('matching.events.v1.OrderPartiallyFilled', (msg) =>
        ordersStore.getState().applyFillUpdate(msg)
      ),
      ws.onMessage('matching.events.v1.OrderFilled', (msg) =>
        ordersStore.getState().applyFillUpdate(msg)
      ),
      ws.onMessage('wallet.events.v1.WalletTransactionRecorded', (msg) =>
        walletStore.getState().applyBalanceChange(msg)
      ),
    ];
    return () => subs.forEach((unsub) => unsub());
  }, [ws, tradeStore, ordersStore, walletStore]);

  return null;
}
```

This keeps WS-handling logic out of individual components — they just read from stores.

---

## 6. API Client & WebSocket Layer

### 6.1 REST Client

`src/lib/api/client.ts`:

```ts
export interface ApiClientOptions {
  baseUrl: string;
  getAccessToken: () => string | null;
  onUnauthorized: () => void;    // trigger refresh / re-auth
}

export class ApiClient {
  constructor(private opts: ApiClientOptions) {}

  async request<T>(method: string, path: string, init: RequestInit = {}): Promise<T> {
    const correlationId = generateCorrelationId();
    const token = this.opts.getAccessToken();

    const headers = new Headers(init.headers);
    headers.set('Content-Type', 'application/json');
    headers.set('X-Correlation-Id', correlationId);
    if (token) headers.set('Authorization', `Bearer ${token}`);

    const res = await fetch(`${this.opts.baseUrl}${path}`, { ...init, method, headers });

    if (res.status === 401) {
      this.opts.onUnauthorized();
      throw new ApiError('TOKEN_EXPIRED', 'Session expired', 401, correlationId);
    }

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(
        body.error?.code ?? 'UNKNOWN',
        body.error?.message ?? res.statusText,
        res.status,
        correlationId,
        body.error?.details
      );
    }

    if (res.status === 204) return undefined as T;
    return res.json() as Promise<T>;
  }

  get<T>(path: string) { return this.request<T>('GET', path); }
  post<T>(path: string, body: unknown) { return this.request<T>('POST', path, { body: JSON.stringify(body) }); }
  delete<T>(path: string) { return this.request<T>('DELETE', path); }
}
```

Endpoint modules wrap the raw client:

```ts
// src/lib/api/endpoints/orders.ts
export function ordersApi(client: ApiClient) {
  return {
    place: (req: PlaceOrderRequest) => client.post<OrderResponse>('/api/v1/orders', req),
    cancel: (orderId: string) => client.delete<OrderResponse>(`/api/v1/orders/${orderId}`),
    list: (params: ListOrdersParams) =>
      client.get<PageResponse<OrderResponse>>(`/api/v1/orders?${qs(params)}`),
    get: (orderId: string) => client.get<OrderResponse>(`/api/v1/orders/${orderId}`),
  };
}
```

### 6.2 WebSocket Client

Heart of real-time UX. Design: **single connection per panel instance**, multiplexing via `channels`:

```ts
// src/lib/ws/WsClient.ts
type Channel = string;      // e.g., "market:BTCUSDT:depth", "orders", "wallet"
type MessageHandler = (payload: any) => void;

export class WsClient {
  private socket: WebSocket | null = null;
  private channelRefs = new Map<Channel, number>();       // ref count per channel
  private schemaHandlers = new Map<string, Set<MessageHandler>>();    // schema → handlers
  private sendQueue: string[] = [];
  private backoff = new ExponentialBackoff(1000, 30_000);
  private state: 'idle' | 'connecting' | 'open' | 'closing' = 'idle';

  constructor(private baseUrl: string, private getToken: () => string | null) {}

  connect() {
    if (this.state !== 'idle') return;
    this.state = 'connecting';
    const token = this.getToken();
    const url = `${this.baseUrl}/ws?token=${encodeURIComponent(token ?? '')}`;
    this.socket = new WebSocket(url);
    this.socket.onopen = () => this.onOpen();
    this.socket.onmessage = (ev) => this.onMessage(ev);
    this.socket.onclose = (ev) => this.onClose(ev);
    this.socket.onerror = (ev) => this.onError(ev);
  }

  subscribe(channel: Channel, handler?: MessageHandler): () => void {
    const prev = this.channelRefs.get(channel) ?? 0;
    this.channelRefs.set(channel, prev + 1);
    if (prev === 0 && this.state === 'open') {
      this.send({ op: 'subscribe', channels: [channel] });
    }
    // optional handler for convenience (schema-based is preferred)
    return () => {
      const curr = this.channelRefs.get(channel) ?? 0;
      if (curr <= 1) {
        this.channelRefs.delete(channel);
        if (this.state === 'open') this.send({ op: 'unsubscribe', channels: [channel] });
      } else {
        this.channelRefs.set(channel, curr - 1);
      }
    };
  }

  onMessage(schema: string, handler: MessageHandler): () => void {
    if (!this.schemaHandlers.has(schema)) this.schemaHandlers.set(schema, new Set());
    this.schemaHandlers.get(schema)!.add(handler);
    return () => this.schemaHandlers.get(schema)?.delete(handler);
  }

  private onOpen() {
    this.state = 'open';
    this.backoff.reset();
    // Re-subscribe to all active channels
    const channels = Array.from(this.channelRefs.keys());
    if (channels.length > 0) this.send({ op: 'subscribe', channels });
    // Flush queue
    this.sendQueue.forEach((msg) => this.socket!.send(msg));
    this.sendQueue = [];
  }

  private onMessage(ev: MessageEvent) {
    const msg = JSON.parse(ev.data);
    const handlers = this.schemaHandlers.get(msg.schema);
    if (handlers) handlers.forEach((h) => h(msg.payload));
  }

  private onClose(ev: CloseEvent) {
    this.state = 'idle';
    this.socket = null;
    if (ev.code === 4401) {
      // Token expired — don't auto-reconnect; let auth layer handle
      return;
    }
    // Auto-reconnect with backoff
    setTimeout(() => this.connect(), this.backoff.next());
  }

  private onError(ev: Event) { /* log, will trigger onClose */ }

  private send(msg: unknown) {
    const s = JSON.stringify(msg);
    if (this.state === 'open') this.socket?.send(s);
    else this.sendQueue.push(s);
  }

  close() {
    this.state = 'closing';
    this.socket?.close();
  }
}
```

### 6.3 `useWsSubscription` Hook

React wrapper — declarative subscription from components:

```tsx
// src/lib/ws/useWsSubscription.ts
export function useWsSubscription(channel: string | null) {
  const ws = useWsClient();
  useEffect(() => {
    if (!channel) return;
    const unsub = ws.subscribe(channel);
    return unsub;
  }, [ws, channel]);
}
```

Usage in a component:

```tsx
function OrderBook({ pair }: { pair: string }) {
  useWsSubscription(`market:${pair}:depth`);
  const depth = useTradeStore((s) => s.depth[pair]);
  return <DepthView depth={depth} />;
}
```

When component mounts, subscription increments ref count (sent to server if first). When unmounts, decrements (unsubscribed if last). Clean, declarative.

### 6.4 Correlation ID

Every REST call generates a fresh UUID correlation-id. Passed as `X-Correlation-Id` header. On error, embedded in the `ApiError.correlationId` for log-tracing by the developer.

WebSocket messages from the server carry `correlation_id` for user-triggered events (e.g., the `OrderFilled` resulting from a place-order call). Not currently used in the UI but preserved for debugging.

---

## 7. TradingView Chart Wrapper

The most sensitive integration. TradingView Lightweight Charts v5 is picky about data shape and lifecycle.

### 7.1 Library Choice & Constraints

- **Lightweight Charts v5**: MIT-licensed, ~40 KB gzipped, no external deps.
- **No UDF adapter needed**: Lightweight consumes data via plain method calls (`series.setData()`, `series.update()`). UDF protocol is for the Advanced Charting Library.
- **The `/udf/*` endpoints still exist** on Market Data Service — they're used directly via `fetch` by our `useChartData` hook, NOT via a UDF adapter.

### 7.2 Component Structure

```tsx
// src/features/chart/CandlestickChart.tsx
export interface CandlestickChartProps {
  pair: string;
  resolution: Resolution;       // '1m' | '5m' | '15m' | '1h' | '4h' | '1d'
  height?: number;
}

export function CandlestickChart({ pair, resolution, height = 500 }: CandlestickChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const ws = useWsClient();

  // 1. Load history via TanStack Query
  const { data: history, isLoading } = useQuery({
    queryKey: ['udf-history', { pair, resolution }],
    queryFn: () => fetchHistory(pair, resolution, defaultRange(resolution)),
    staleTime: 60_000,
  });

  // 2. Create/destroy chart on mount/unmount
  useEffect(() => {
    if (!containerRef.current) return;
    const chart = createChart(containerRef.current, chartOptions);
    const series = chart.addCandlestickSeries(seriesOptions);
    chartRef.current = chart;
    seriesRef.current = series;

    const onResize = () => chart.applyOptions({ width: containerRef.current!.clientWidth });
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  // 3. Set data when history arrives OR when pair/resolution changes
  useEffect(() => {
    if (!seriesRef.current || !history) return;
    seriesRef.current.setData(history);
  }, [history]);

  // 4. Subscribe to live kline updates
  useEffect(() => {
    if (!seriesRef.current) return;
    const channel = `market:${pair}:kline:${resolution}`;
    const unsubChannel = ws.subscribe(channel);
    const unsubHandler = ws.onMessage('market-data.kline.v1', (msg) => {
      if (msg.pair !== pair || msg.interval !== resolution) return;
      seriesRef.current?.update(klineToBar(msg));
    });
    return () => { unsubChannel(); unsubHandler(); };
  }, [ws, pair, resolution]);

  return (
    <div className="hx-relative" style={{ height }}>
      {isLoading && <ChartSkeleton />}
      <div ref={containerRef} className="hx-w-full hx-h-full" />
    </div>
  );
}
```

### 7.3 Data Shape Mapping

`/udf/history` returns compact format `{s:"ok", t, o, h, l, c, v}`. Transform to Lightweight's shape:

```ts
// src/features/chart/useChartData.ts
interface Bar {
  time: number;     // seconds, UTC
  open: number;
  high: number;
  low: number;
  close: number;
}

async function fetchHistory(pair: string, resolution: Resolution, range: { from: number; to: number }): Promise<Bar[]> {
  const res = await fetch(
    `${gateway}/udf/history?symbol=${pair}&resolution=${toUdfRes(resolution)}&from=${range.from}&to=${range.to}`
  );
  const data = await res.json();
  if (data.s !== 'ok') return [];
  return data.t.map((time: number, i: number) => ({
    time,
    open: parseFloat(data.o[i]),
    high: parseFloat(data.h[i]),
    low: parseFloat(data.l[i]),
    close: parseFloat(data.c[i]),
  }));
}
```

Lightweight Charts expects numbers, not strings. Parse once at the boundary; never let strings propagate.

### 7.4 Lifecycle Gotchas (from experience)

1. **`chart.remove()` MUST be called on unmount.** Otherwise memory leaks accumulate on navigation.
2. **Don't recreate the chart when `pair` changes** — call `series.setData(newBars)` instead. The chart instance is reusable.
3. **`series.update(bar)` for live bars** — must have same `time` as the in-progress bar OR a new bar timestamp. Out-of-order `update` calls silently misbehave.
4. **ResizeObserver** is overkill; window `resize` + manual `applyOptions({ width })` is sufficient for MVP.
5. **SSR pitfall**: `createChart` accesses `window`. The component is client-only; in standalone Next.js, wrap the route in `'use client'`. In embedded mode, `HaizzTradingPanel` is already lazy-loaded with `ssr: false`.

### 7.5 Migration Path to Advanced Charting Library (Post-MVP)

When upgrading:
1. Replace `createChart` with `new TradingView.widget(...)`.
2. Create a **UDF DataFeed adapter** (JS class implementing TV's DataFeed interface) that wraps our `/udf/*` REST endpoints + WS kline stream.
3. Update Market Data Service to fully comply with UDF protocol (symbol search, marks, timescale marks — currently skipped).
4. Distribute TV's Charting Library JS via our `public/` dir (or CDN) — not npm.

The current Lightweight wrapper is isolated enough that this upgrade touches only `features/chart/` and corresponding Market Data Service endpoints.

---

## 8. Authentication Flows (Both Modes)

### 8.1 Standalone Mode (Stage 1)

- Login/register via `POST /auth/login`, `POST /auth/register`.
- Access token: held in memory (`authStore.accessToken`).
- Refresh token: stored in `httpOnly` cookie (set by Gateway on login response).
- Refresh flow: on 401 from API, call `POST /auth/refresh` (cookie auto-sent), get new access token.
- Logout: `POST /auth/logout` (clears cookie) + clear in-memory token + redirect to `/login`.

```ts
// src/lib/auth/AuthStandaloneProvider.tsx
export function AuthStandaloneProvider({ children }) {
  const [accessToken, setAccessToken] = useState<string | null>(null);

  const refresh = async () => {
    const res = await fetch(`${gateway}/auth/refresh`, { method: 'POST', credentials: 'include' });
    if (!res.ok) throw new Error('Refresh failed');
    const { accessToken: newToken } = await res.json();
    setAccessToken(newToken);
    return newToken;
  };

  const apiClient = useMemo(() => new ApiClient({
    baseUrl: gateway,
    getAccessToken: () => accessToken,
    onUnauthorized: async () => {
      try { await refresh(); } catch { redirectToLogin(); }
    },
  }), [accessToken]);

  return <AuthContext.Provider value={{ accessToken, setAccessToken, apiClient }}>{children}</AuthContext.Provider>;
}
```

### 8.2 Embedded Mode (Stage 2) — Auth Bridge

Host owns auth. Panel does NOT call `/auth/login`. Instead, host provides an `accessToken` prop and optionally a `refreshCallback`:

```ts
// src/lib/auth/AuthBridge.ts
export function AuthBridgeProvider({ auth, mode, children }) {
  if (mode === 'standalone') return <AuthStandaloneProvider>{children}</AuthStandaloneProvider>;

  // Embedded mode
  const [accessToken, setAccessToken] = useState<string | null>(auth?.accessToken ?? null);

  // Sync prop changes (host refreshed externally)
  useEffect(() => { setAccessToken(auth?.accessToken ?? null); }, [auth?.accessToken]);

  const refresh = async () => {
    if (!auth?.refreshCallback) {
      auth?.onAuthExpired?.();
      throw new Error('No refresh callback provided; host must handle re-auth');
    }
    const newToken = await auth.refreshCallback();
    setAccessToken(newToken);
    return newToken;
  };

  const apiClient = useMemo(() => new ApiClient({
    baseUrl: gateway,
    getAccessToken: () => accessToken,
    onUnauthorized: async () => {
      try { await refresh(); } catch { auth?.onAuthExpired?.(); }
    },
  }), [accessToken]);

  return <AuthContext.Provider value={{ accessToken, apiClient }}>{children}</AuthContext.Provider>;
}
```

**Contract with host (documented in npm README):**

```tsx
<HaizzTradingPanel
  mode="embedded"
  auth={{
    accessToken: 'eyJhbGc...',           // current token
    refreshCallback: async () => {
      // host's logic to get a fresh token from its own auth system
      return await myHostAuth.refresh();
    },
    onAuthExpired: () => {
      // called when refresh fails or no refreshCallback provided
      myHostApp.redirectToLogin();
    },
  }}
/>
```

The `accessToken` passed by host must be **exchangeable at Gateway's `/auth/sso/exchange` endpoint** for a Haizz-issued JWT OR be a Haizz-issued JWT directly. This contract is Stage 2's SSO handshake — detailed in `SystemDesign_Appendix_UserAuthService.md` (pending).

### 8.3 WS Authentication

WS connection URL includes token as query param: `wss://gateway/ws?token=<jwt>`.

Why query param and not Authorization header? The browser `WebSocket` API does not support custom headers. Query param is standard practice; token is short-lived (1 h) so URL logging risk is bounded.

On token expiry mid-connection: Gateway closes with code `4401`. Client catches this, triggers refresh via `AuthBridge.refresh()`, then reconnects with new token.

---

## 9. Routing Strategy

### 9.1 Route Registry

| Logical Route | Standalone URL | Embedded `PanelRoute` |
|---------------|----------------|----------------------|
| Login | `/login` | `{ screen: 'login' }` |
| Register | `/register` | (N/A — embedded host handles user creation) |
| Trade (default pair) | `/trade` | `{ screen: 'trade', pair: defaultPair }` |
| Trade (specific pair) | `/trade/[pair]` | `{ screen: 'trade', pair }` |
| Wallet overview | `/wallet` | `{ screen: 'wallet' }` |
| Open orders + history | `/orders` | `{ screen: 'orders' }` |
| Trade history | `/trades` | `{ screen: 'trades' }` |
| Deposit | `/deposit` | `{ screen: 'deposit' }` |

### 9.2 Deep-Link Handling (Embedded)

Host can pass `initialRoute` prop to jump directly:

```tsx
<HaizzTradingPanel
  mode="embedded"
  initialRoute={{ screen: 'trade', pair: 'ETHUSDT' }}
/>
```

For URL sync in embedded mode (optional, host-controlled): host listens to `onEvent({ type: 'navigate', route })` and can update its own URL:

```tsx
<HaizzTradingPanel
  onEvent={(ev) => {
    if (ev.type === 'navigate' && ev.route.screen === 'trade') {
      router.replace(`/academy/trading/${ev.route.pair}`);
    }
  }}
/>
```

### 9.3 Auth Guards

- **Standalone**: each `(trader)/*` route wraps in a `<RequireAuth>` client component that redirects to `/login` if unauthed.
- **Embedded**: `PanelRouter` forces `{ screen: 'login' }` if `!isAuthed`. No URL redirect — the login screen appears inside the panel boundary.

---

## 10. Styling & Theming

### 10.1 Tailwind with Prefix

`tailwind.config.js`:

```js
module.exports = {
  prefix: 'hx-',
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        'hx-bg': 'var(--hx-bg, #ffffff)',
        'hx-surface': 'var(--hx-surface, #f5f5f5)',
        'hx-text': 'var(--hx-text, #111111)',
        'hx-primary': 'var(--hx-primary, #2962ff)',
        'hx-success': 'var(--hx-success, #26a69a)',
        'hx-danger': 'var(--hx-danger, #ef5350)',
      },
    },
  },
};
```

Every utility class in our code is prefixed: `hx-flex`, `hx-items-center`, `hx-bg-hx-primary`. No collision with host's Tailwind (even if host uses unprefixed).

### 10.2 CSS Scoping

The panel root sets a wrapping class:

```tsx
<div className="haizz-panel" data-theme={theme} data-mode={mode}>
  {/* everything inside */}
</div>
```

All global-ish styles are scoped inside `.haizz-panel`:

```css
/* src/styles/panel.css */
.haizz-panel {
  font-family: Inter, system-ui, sans-serif;
  color: var(--hx-text);
  background: var(--hx-bg);
  --hx-bg: #ffffff;
  --hx-surface: #f5f5f5;
  --hx-text: #111111;
  --hx-primary: #2962ff;
  /* ... */
}

.haizz-panel[data-theme="dark"] {
  --hx-bg: #1a1a1a;
  --hx-surface: #2a2a2a;
  --hx-text: #e5e5e5;
  /* ... */
}

.haizz-panel[data-theme="inherit"] {
  /* Variables come from host — don't override */
  --hx-bg: var(--theme-bg, #ffffff);
  --hx-primary: var(--theme-primary, #2962ff);
  /* ... */
}
```

### 10.3 Avoiding Global Leaks

- **No `globals.css` imported in `panel/` code.** Only in `src/app/layout.tsx` for standalone.
- **No CSS resets** — scoped Tailwind preflight is limited with `corePlugins: { preflight: false }` in embedded builds to prevent host style resets.
- **No injected `<style>` tags** beyond what bundlers/CSS-in-JS already do, scoped to `.haizz-panel`.

---

## 11. Build, Bundle & Publish

### 11.1 Two Build Targets

**Target 1: Standalone Next.js app (Stage 1)**
```bash
next build && next start
```
Runs on port 3000 in docker-compose.

**Target 2: Library bundle for npm (Stage 2)**
```bash
rollup -c rollup.config.mjs
# outputs:
#   dist/index.js       (ESM)
#   dist/index.cjs.js   (CJS)
#   dist/index.d.ts     (types)
#   dist/styles.css     (Tailwind compiled)
```

### 11.2 Rollup Config Essentials

```js
// rollup.config.mjs
export default {
  input: 'src/panel/HaizzTradingPanel.tsx',
  output: [
    { file: 'dist/index.js', format: 'es', sourcemap: true },
    { file: 'dist/index.cjs.js', format: 'cjs', sourcemap: true },
  ],
  external: ['react', 'react-dom', 'next/dynamic'],
  plugins: [
    typescript({ tsconfig: './tsconfig.lib.json' }),
    postcss({ extract: 'styles.css', modules: false, plugins: [tailwindcss, autoprefixer] }),
    resolve(),
    commonjs(),
  ],
};
```

**Externals**: `react`, `react-dom`, `next/dynamic` are peer deps — not bundled. Host provides them (shared instance critical).

### 11.3 package.json (library export)

```json
{
  "name": "@haizz/trading-panel",
  "version": "0.1.0",
  "main": "dist/index.cjs.js",
  "module": "dist/index.js",
  "types": "dist/index.d.ts",
  "exports": {
    ".": {
      "import": "./dist/index.js",
      "require": "./dist/index.cjs.js",
      "types": "./dist/index.d.ts"
    },
    "./styles.css": "./dist/styles.css"
  },
  "sideEffects": ["*.css"],
  "peerDependencies": {
    "react": ">=18",
    "react-dom": ">=18",
    "next": ">=14"
  },
  "dependencies": {
    "lightweight-charts": "^5.0.0",
    "zustand": "^4.5.0",
    "@tanstack/react-query": "^5.0.0",
    "react-hook-form": "^7.50.0",
    "zod": "^3.22.0",
    "lucide-react": "^0.350.0",
    "clsx": "^2.1.0"
  }
}
```

### 11.4 Host Integration Steps (Stage 2)

Documented in README:

```bash
npm install @haizz/trading-panel
```

In host's layout/page:

```tsx
import '@haizz/trading-panel/styles.css';    // once, at app entry
import dynamic from 'next/dynamic';

const HaizzTradingPanel = dynamic(
  () => import('@haizz/trading-panel').then(m => m.HaizzTradingPanel),
  { ssr: false }
);

export default function TradingLesson() {
  return (
    <HaizzTradingPanel
      mode="embedded"
      auth={{ accessToken: myToken, refreshCallback: myRefresh }}
      gatewayBaseUrl={process.env.NEXT_PUBLIC_HAIZZ_GATEWAY!}
      theme="inherit"
    />
  );
}
```

### 11.5 Publishing Workflow

- **Versioning**: semver. Major bump for `HaizzTradingPanelProps` breaking changes.
- **Changelog**: `CHANGELOG.md` maintained manually.
- **CI/CD**: GitHub Actions on tag push → build + test + `npm publish`. MVP: manual publish.
- **Distribution**: public npm for now; private registry if host integration requires isolation.

---

## 12. Configuration & Environment

### 12.1 Environment Variables (Standalone)

```
NEXT_PUBLIC_GATEWAY_URL=http://localhost:8080
NEXT_PUBLIC_DEFAULT_PAIR=BTCUSDT
NEXT_PUBLIC_SUPPORTED_PAIRS=BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT
```

Prefix `NEXT_PUBLIC_` required for client-side access in Next.js.

### 12.2 Runtime Configuration (Embedded)

All config passed via props:

```tsx
interface PanelConfig {
  mode: 'standalone' | 'embedded';
  gatewayBaseUrl: string;
  locale?: string;
  theme?: 'light' | 'dark' | 'inherit';
  defaultPair?: string;
  supportedPairs?: string[];        // override the default hardcoded list
  onEvent?: (event: HaizzEvent) => void;
}
```

Read via `usePanelConfig()` hook anywhere in the tree.

### 12.3 `HaizzEvent` Types (for `onEvent`)

Host-facing event stream — useful for analytics/tracking:

```ts
type HaizzEvent =
  | { type: 'auth.expired' }
  | { type: 'navigate'; route: PanelRoute }
  | { type: 'order.placed'; orderId: string; pair: string }
  | { type: 'order.cancelled'; orderId: string }
  | { type: 'error'; error: Error; info?: React.ErrorInfo };
```

---

## 13. Error Handling & UX

### 13.1 Error Surface Layers

| Source | Mechanism | User-Visible Result |
|--------|-----------|---------------------|
| Form validation (Zod) | Inline field errors | Red text under field, submit disabled |
| API error (400 with known code) | Toast + inline message | Specific message: "Insufficient balance. Available: 4,000 USDT." |
| API error (401) | Auto-refresh, then retry | Silent if refresh succeeds; else redirect to login |
| API error (429) | Toast with countdown | "Too many requests. Try again in 30s." |
| API error (503 — PRICE_FEED_UNAVAILABLE) | Banner at top of trade screen | "Market data unavailable. Trading paused." |
| API error (500) | Toast | "Something went wrong. Please try again. [correlation-id: abc...]" |
| Network failure | TanStack Query retry (1×) + toast | "Network error. Check your connection." |
| WS disconnect | Small banner | "Live updates reconnecting..." (auto-clears on reconnect) |
| Uncaught exception | ErrorBoundary | Full-screen error (standalone) / contained box (embedded) |

### 13.2 Common UX Patterns

**Order placement feedback:**
1. User clicks Submit → button enters loading state (spinner + disabled).
2. On 201: toast success "Order placed", form resets, button re-enables.
3. On 400: toast error with specific message, form stays filled so user can fix.
4. On 503: toast + banner hint to check feed status.

**Real-time balance update:** When WS delivers `WalletTransactionRecorded`, the wallet card for that asset briefly pulses (CSS animation) to draw attention. No toast — passive update.

**Order cancellation feedback:**
1. User clicks Cancel → confirmation dialog.
2. On confirm → DELETE request → optimistic update (order row grays out + "Cancelling..." badge).
3. On 200 → wait for WS `OrderCancelled` event → badge changes to "Cancelled", row remains visible for 10s then moves to history.
4. On 409 `ORDER_NOT_CANCELLABLE` → toast + revert optimistic update.

### 13.3 Loading States

- Initial app load: skeleton screens for each major panel (wallet cards, order book, chart).
- Data refresh: no blocking spinner; subtle "updating" indicator only if refresh > 500 ms.
- Infinite scroll lists: skeleton rows at bottom while fetching.

### 13.4 Empty States

- No orders → friendly illustration + "Place your first order to start trading."
- No trade history → "Your executed trades will appear here."
- No wallets (shouldn't happen — bootstrap creates 6) → "Your account is being set up..."

---

## 14. Testing Strategy

### 14.1 Test Pyramid

| Layer | Count | Tool | What's Tested |
|-------|-------|------|---------------|
| Unit — utils | ~40 | Vitest | BigNumber formatting, correlation-id gen, date helpers |
| Unit — hooks | ~20 | Vitest + @testing-library/react-hooks | `useWsSubscription`, `usePlaceOrder`, form validation |
| Unit — stores | ~15 | Vitest | Zustand state mutations, selectors |
| Component | ~50 | Vitest + Testing Library | `OrderForm` validation, `OrderBook` rendering, `CandlestickChart` mount/unmount |
| Integration — API | ~15 | MSW (Mock Service Worker) | REST client retry, auth refresh flow |
| Integration — WS | ~10 | `mock-socket` library | Subscribe/unsubscribe ref counting, reconnect with backoff |
| E2E | ~10 | Playwright | Full user journey: login → place order → see fill → wallet updates |
| Visual regression | ~10 | Playwright + screenshot diff | Critical screens under light/dark theme |

### 14.2 Critical Test Scenarios

**Dual-mode:**
- `HaizzTradingPanel_standaloneMode_usesInternalAuth`
- `HaizzTradingPanel_embeddedMode_usesProvidedToken`
- `HaizzTradingPanel_embeddedMode_missingAuth_showsError`
- `HaizzTradingPanel_embeddedMode_tokenRefreshCalled_onUnauthorized`

**Chart:**
- `CandlestickChart_mountsAndRendersData`
- `CandlestickChart_unmountsCleanly_noMemoryLeak`
- `CandlestickChart_pairChange_callsSetData_notRecreate`
- `CandlestickChart_liveUpdate_callsSeriesUpdate`

**WebSocket:**
- `WsClient_subscribeTwice_sendsOneSubscribeMessage`
- `WsClient_unsubscribeLast_sendsUnsubscribeMessage`
- `WsClient_disconnect_reconnectsWithExponentialBackoff`
- `WsClient_tokenExpiredClose_doesNotAutoReconnect`

**Forms:**
- `OrderForm_limitBuy_freezeEstimateShownCorrectly`
- `OrderForm_exceedsBalance_submitDisabled`
- `OrderForm_invalidTickSize_showsInlineError`

**Integration:**
- `placeOrder_successFlow_toastShown_ordersListInvalidated`
- `placeOrder_insufficientBalance_errorSurfacedToForm`
- `cancelOrder_optimistic_revertsOn409`

### 14.3 MSW Setup

```ts
// tests/msw/handlers.ts
export const handlers = [
  rest.post('/api/v1/orders', async (req, res, ctx) => {
    return res(ctx.status(201), ctx.json({ orderId: 'mock-id', state: 'NEW' }));
  }),
  // ...
];
```

Enables tests to run without a real backend. For E2E, run against full docker-compose stack.

### 14.4 Storybook (Optional but Recommended)

Stories for each UI primitive and feature component. Useful for:
- Visual QA during development.
- Component review without mounting the full app.
- Documenting prop interfaces for future contributors.

Skip in MVP if time-tight; add post-MVP.

---

## 15. Open Implementation Notes

1. **Internationalization.** `locale` prop is wired but MVP ships English-only. When adding i18n: use `next-intl` or `react-intl`; extract strings via `t('key')` calls now so future extraction is a mechanical find-replace.

2. **Accessibility.** Use semantic HTML throughout (`<button>`, `<label>`, `<nav>`). Forms have proper label associations. Tables use `<thead>`/`<tbody>`. Color contrast meets WCAG AA. ARIA labels on chart widget since Lightweight Charts renders canvas (not screen-reader friendly out of the box).

3. **Performance.** Critical rendering path: login → trade screen with chart in < 2s on cold cache. Measurement: Next.js built-in metrics (`/measure` route in dev). Post-MVP: Lighthouse CI.

4. **Chart on touch devices.** Lightweight Charts supports touch gestures, but UI chrome (toolbar, pair selector) needs tap-friendly sizing. Mobile is out of scope for MVP but don't actively break it.

5. **Optimistic UI depth.** Orders: optimistic on place (show NEW immediately), not optimistic on cancel (wait for confirmation, use loading state). Rationale: cancel failure is rare but visible; place success is expected.

6. **Toast positioning.** Top-right by default. In embedded mode, host may want to disable our toasts and use its own — add `suppressToasts: boolean` to config in future iteration.

7. **Bundle size budget.** Target < 200 KB gzipped for the library (excluding peer deps). Lightweight Charts alone is ~40 KB; Zustand ~3 KB; React Query ~12 KB. Feature code should stay under 100 KB.

8. **React 19.** Not using for MVP (still early; peer dep = >=18). Revisit when Next 15 stabilizes.

9. **Server Components in embedded mode.** Not allowed — the library is client-only. `'use client'` directive at every entry point. ESLint rule: no server-only imports in `panel/` or `features/`.

10. **Pair watchlist.** Not in MVP but frequently requested in similar products. When added: new feature in `features/watchlist/`, persisted in user profile (Auth Service extension) or local Zustand with `persist` middleware.

11. **WebSocket reconnection while refresh is in flight.** Edge case: WS closes with 4401, triggers refresh; during refresh, another API call also gets 401 and tries to refresh. Solution: `refresh()` is idempotent via a shared promise — all callers await the same in-flight refresh. Implement in `AuthBridge`.

12. **Version matrix with host app.** Peer deps declare `>=18` for React, `>=14` for Next. Test against host's actual version during integration. Any divergence in React major version = breaking.

---

*End of `SystemDesign_Appendix_Frontend.md`.*
