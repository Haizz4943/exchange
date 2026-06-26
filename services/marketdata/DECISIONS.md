# Market Data Service — Implementation Decisions

Per-service log of judgment calls not dictated by the specs (SRS / System Design /
API_SPEC / CLAUDE.md). Review and back-port into the official docs as needed.

## 2026-06-25 — Short retention on `market-data.events.v1` (bound the ephemeral firehose)
**Status:** 🟡 Pending review
**Decision:** Added a `KafkaAdmin` (`modifyTopicConfigs(true)`) + `NewTopic` in `KafkaConfig`
that declares `market-data.events.v1` with `retention.ms=600000` (10 min) and
`segment.ms=60000` (1 min). Configurable under `market.kafka.events-topic.*`.
**Why:** The topic is a high-rate ephemeral firehose (see the 2026-06-24 entry) and had **no
retention** (broker default), growing to ~13.5M records. `modifyTopicConfigs(true)` applies the
config to the already auto-created topic, not just on first creation; the short `segment.ms`
lets closed segments roll so `retention.ms` actually reclaims them. Complements the matching-side
fix (matching seeks-to-end on startup so it never replays the backlog) — see
`services/matching/DECISIONS.md` (2026-06-25). Verified: topic now reports
`retention.ms=600000, segment.ms=60000`.
**Where:** `services/marketdata/.../config/KafkaConfig.java`,
`services/marketdata/src/main/resources/application.yml`.

## 2026-06-24 — `ExternalTradeObserved` publishes directly (ephemeral), not via durable outbox
**Status:** 🟡 Pending review
**Decision:** External trade observations are now published **directly** to
`market-data.events.v1` via `ephemeralKafkaTemplate` (acks=1, no idempotence, fire-and-forget),
through a new `MarketDataEventPublisher.publishExternalTrade(...)`. They no longer go through the
durable `market_data_outbox` + relay. The payload stays wrapped in `EventEnvelope` (eventType
`ExternalTradeObservedEvent`) so the matching consumer contract is unchanged. Feed-health
(`MarketDataFeedDegraded/Recovered`) and pair-metadata events still use the durable outbox.
**Why:** Every Binance trade for all 5 pairs (a high-rate "firehose", hundreds/sec) was being
written to the durable outbox, while the shared `OutboxRelay` publishes synchronously one message
at a time (`send().get()`, ~58 msg/s). Write-rate >> publish-rate → the outbox backlog grew
without bound (observed **8.4M unpublished rows, ~6 days stale**). Because the relay drains FIFO
oldest-first, Kafka's tail carried **week-old prices**, so the matching engine matched resting
limit orders against stale prices and live-priced orders never became eligible — i.e. "open orders
never match". Trades are ephemeral market data (same class as depth/kline, which already publish
directly); dropping a few during a Kafka blip is acceptable, and matching never retroactively fills
from buffered trades anyway.
**Deviation:** SRS_Appendix_MatchingEngine / SRS_Appendix_MarketDataService and
`V3__create_market_data_outbox.sql` state ExternalTradeObserved "must survive Kafka outages"
(durable). This change trades that durability for bounded, always-fresh behavior.
**Where:**
- `services/marketdata/.../infrastructure/messaging/producer/MarketDataEventPublisher.java`
  (new `publishExternalTrade`)
- `services/marketdata/.../application/ingestion/TradeIngestionService.java`
  (calls publisher instead of `outbox.write`)
**Suggested doc:** SRS/SystemDesign Market Data + Matching appendices — reclassify
ExternalTradeObserved as best-effort ephemeral; note durability is intentionally not guaranteed.
