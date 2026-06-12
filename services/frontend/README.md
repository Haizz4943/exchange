# Haizz Trading Panel — Frontend

Standalone Next.js trading panel for Haizz Exchange.  
Port: **3000** (dev).  
Also architected for Stage 2 embedded/npm-library mode (see `src/panel/HaizzTradingPanel.tsx`).

---

## Quick Start

```bash
cp .env.example .env.local
# Edit .env.local with your gateway URL

npm install
npm run dev        # → http://localhost:3000
# or
npm run build && npm start
```

### Type check only (no build required)

```bash
npm run type-check   # runs tsc --noEmit
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `NEXT_PUBLIC_GATEWAY_URL` | `http://localhost:8080` | API Gateway base URL |
| `NEXT_PUBLIC_DEFAULT_PAIR` | `BTCUSDT` | Default trading pair on load |
| `NEXT_PUBLIC_SUPPORTED_PAIRS` | `BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT` | Comma-separated list of pairs |

---

## What Is Wired vs Stubbed

### Fully Wired (functional when backend is up)

| Feature | Status | Backend Dependency |
|---------|--------|--------------------|
| Register/Login forms | Wired — RHF + Zod validation + real API call | Auth Service (:8081) |
| Token refresh (standalone) | Wired — auto-refresh on 401 | Auth Service (:8081) |
| Wallet overview | Wired — TanStack Query + real API call | Wallet Service (:8082) |
| Deposit form | Wired — form + real API call | Wallet Service (:8082) |
| Withdrawal form | Wired — form + real API call | Wallet Service (:8082) |
| Candlestick chart | Wired — lightweight-charts v5, fetches `/udf/history`, live WS kline updates | Market Data Service (:8084) |
| WS infrastructure | Wired — multiplexed client, ref-counted subscriptions, exponential backoff reconnect | API Gateway WS endpoint |
| WS → Zustand store sync | Wired — `WsStoreSyncer` dispatches all known schemas | Gateway WS |
| Auth-guarded routes | Wired — redirect to /login if unauthed | — |

### Stubbed (structure + props contract; no real backend calls)

| Feature | Stub Notes |
|---------|------------|
| Order placement (`OrderForm`) | Form is validated but submit is disabled with a clear notice. Requires Order Service (not deployed). |
| Order Book (`OrderBook`) | Renders live from Zustand if WS data arrives; shows a "Gateway not available" notice if empty. |
| Recent Trades (`TradesTape`) | Same as Order Book. |
| Open Orders table | Makes real API call to Order Service; gracefully shows error banner if unavailable. |
| Order History table | Same as above. |
| Trade History table | Makes real API call to Trade Service; gracefully shows error banner if unavailable. |

---

## External Dependencies Not Yet Available

| Service | Why Needed | Impact |
|---------|-----------|--------|
| **API Gateway** (`:8080`) | Routes all REST + WebSocket | Nothing works without it |
| **WebSocket Gateway** | Real-time depth, ticker, kline, fills | OrderBook, TradesTape, live chart updates show stubs |
| **Order Service** (`:8083`) | Place/cancel/list orders | Order form disabled; orders tables show API error |
| **Matching Engine** | Fills orders, publishes fill events | No live fill updates |

Auth Service and Wallet Service are **already deployed** — login/register and deposit/withdraw work.

---

## Architecture Overview

```
src/
├── app/           Next.js App Router (standalone only)
├── panel/         HaizzTradingPanel — embedded entry point
├── features/      Feature modules (shared core)
│   ├── auth/      Login, register forms + hooks
│   ├── trade/     TradeScreen, OrderBook, OrderForm, TradesTape, PairSelector
│   ├── wallet/    WalletOverview, DepositDialog, WithdrawDialog
│   ├── orders/    OpenOrdersTable, OrderHistoryTable
│   ├── trades/    TradeHistoryTable
│   └── chart/     CandlestickChart (lightweight-charts v5), useChartData
├── lib/
│   ├── api/       ApiClient + typed endpoint modules
│   ├── ws/        WsClient (multiplexed), WsProvider, useWsSubscription
│   ├── auth/      AuthStandaloneProvider, AuthBridge
│   ├── config/    PanelConfig context
│   └── utils/     bigNumber formatting, date/time, correlationId
└── components/
    ├── ui/        Button, Input, Select, Modal, Table, Skeleton, Toast
    └── layout/    Header, Sidebar, ErrorBoundary
```

### Key Design Decisions

1. **Zustand stores are instance-scoped** via React context. Multiple panel embeds on the same host page each get isolated state.
2. **Single WS connection per panel** — multiplexed via `WsClient`. Subscriptions are ref-counted: first subscriber sends `SUBSCRIBE`, last unsubscribe sends `UNSUBSCRIBE`.
3. **No server actions** — all data goes through the Gateway REST/WSS API. Makes the frontend portable to embedded mode.
4. **Tailwind prefix `hx-`** — zero collision with host apps even if they use Tailwind.
5. **Chart is SSR-safe** — lightweight-charts is imported dynamically inside a `useEffect` (window access).

---

## Files Worth Reviewing First

1. `src/lib/ws/WsClient.ts` — WebSocket multiplexing engine
2. `src/panel/HaizzTradingPanel.tsx` — embedded mode entry point + props interface
3. `src/panel/WsStoreSyncer.tsx` — WS message → Zustand dispatch
4. `src/features/chart/CandlestickChart.tsx` — lightweight-charts v5 integration
5. `src/features/chart/useChartData.ts` — UDF history fetch + Bar mapping
6. `src/lib/api/client.ts` — REST client with correlation-id + 401 handling
7. `src/app/providers.tsx` — standalone app providers composition
