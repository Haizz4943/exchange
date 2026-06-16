package com.haizz.exchange.matching.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Snake_case paged envelope mirroring the Order service's {@code PageResponse} shape so the
 * gateway-facing contract is consistent across services.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages,
        boolean first,
        boolean last
) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
