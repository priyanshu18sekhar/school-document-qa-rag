package com.docqa.rag.document.dto;

import java.util.List;

/**
 * A page of results.
 *
 * <p>Hand-rolled rather than Spring Data's {@code Page} because Spring Data is
 * not a dependency of this project - the persistence layer is plain
 * {@link org.springframework.jdbc.core.simple.JdbcClient}. Pulling in
 * spring-data-commons purely for a DTO shape would add an ORM's worth of
 * autoconfiguration for four fields.
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PagedResponse<>(items, page, size, totalElements, totalPages);
    }
}
