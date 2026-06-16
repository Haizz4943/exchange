package com.haizz.exchange.order.api;

import com.haizz.exchange.order.api.dto.InternalOrderProjection;
import com.haizz.exchange.order.api.dto.PageResponse;
import com.haizz.exchange.order.application.ListOpenOrdersUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (network-trust, no JWT) order endpoints for the Matching Engine.
 * Permitted under {@code /api/v1/orders/internal/**} in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/orders/internal")
@RequiredArgsConstructor
public class InternalOrderController {

    private final ListOpenOrdersUseCase listOpenOrdersUseCase;

    /**
     * Open-orders projection for Matching Engine startup index rebuild (API_SPEC §3.7).
     * Returns ALL users' orders in the given states (default OPEN,PARTIALLY_FILLED)
     * ordered FIFO by createdAt ASC. The engine iterates pages until empty.
     */
    @GetMapping("/orders")
    @ResponseStatus(HttpStatus.OK)
    public PageResponse<InternalOrderProjection> listOpenOrders(
            @RequestParam(defaultValue = "OPEN,PARTIALLY_FILLED") String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size) {
        return listOpenOrdersUseCase.execute(state, page, size);
    }
}
