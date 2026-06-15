package com.haizz.exchange.order.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Snake_case paged response wrapper matching API_SPEC §3 / §3.7
 * (<code>content / page / size / total_elements / total_pages</code>).
 * <p>
 * Spring Data's raw {@code Page} serialization does not match this contract,
 * so list endpoints map {@code Page<Entity>} into this DTO explicitly.
 */
public record PageResponse<T>(
        @JsonProperty("content") List<T> content,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages
) {
    /** Maps a Spring Data {@link Page} of entities into a {@code PageResponse} of DTOs. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
