package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.matching.domain.OpenOrdersIndex;
import com.haizz.exchange.matching.domain.ResidentOrder;
import com.haizz.exchange.matching.infrastructure.client.OrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the in-memory {@link OpenOrdersIndex} on startup by paging the Order service's
 * internal open-orders projection.
 *
 * <p><b>Resilience:</b> if the Order service is unreachable the app boots anyway in a
 * degraded state — we log a warning and continue (consumers will still process live events).
 * A future enhancement could schedule a retry. See DECISIONS.md.
 *
 * <p><b>Idempotency:</b> each order is {@code remove}d before {@code add} so re-running the
 * rebuild produces a clean index without duplicates.
 *
 * <p>Runs at {@link ApplicationReadyEvent} time, before live traffic matters; index mutation
 * here is done directly (single rebuild thread) — once consumers drive events, all further
 * mutation goes through the per-pair executor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRebuildService {

    private static final int PAGE_SIZE = 200;

    private final OrderClient orderClient;
    private final OpenOrdersIndex openOrdersIndex;

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        log.info("Starting open-orders index rebuild from Order service...");
        int loaded = 0;
        int skipped = 0;
        try {
            int page = 0;
            while (true) {
                OrderClient.PagedOrders paged = orderClient.fetchOpenOrders(page, PAGE_SIZE);
                if (paged == null || paged.content() == null || paged.content().isEmpty()) {
                    break;
                }
                for (OrderClient.InternalOrderDto dto : paged.content()) {
                    OrderType type = OrderType.valueOf(dto.type());
                    if (type != OrderType.LIMIT) {
                        // MARKET orders should never be resting; skip defensively.
                        skipped++;
                        continue;
                    }
                    ResidentOrder ro = new ResidentOrder(
                            dto.id(),
                            dto.userId(),
                            dto.pair(),
                            OrderSide.valueOf(dto.side()),
                            type,
                            dto.quantity(),
                            dto.limitPrice(),
                            dto.createdAt(),
                            dto.filledQuantity()
                    );
                    // Idempotent: drop any stale copy first.
                    openOrdersIndex.remove(ro.getOrderId());
                    openOrdersIndex.add(ro);
                    loaded++;
                }
                if (paged.content().size() < PAGE_SIZE
                        || (paged.totalPages() > 0 && page >= paged.totalPages() - 1)) {
                    break;
                }
                page++;
            }
            log.info("Open-orders index rebuild complete: loaded={} LIMIT order(s), skipped={} non-LIMIT",
                    loaded, skipped);
        } catch (Exception e) {
            // TODO(later): schedule a retry / reconnect to recover the index.
            log.warn("Open-orders index rebuild failed; booting in DEGRADED state "
                    + "(loaded {} before failure). Live events will still be processed. Cause: {}",
                    loaded, e.toString());
        }
    }
}
