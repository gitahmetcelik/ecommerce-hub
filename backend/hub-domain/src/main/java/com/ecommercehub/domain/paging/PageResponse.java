package com.ecommercehub.domain.paging;

import java.util.List;

/**
 * Plan v5 §7.2 point 5's common shape: {@code page}, {@code size}, {@code total}, {@code
 * items}. {@code total} is what lets the frontend render "5,000 varyant · sayfa 1/84"
 * (Plan §U2) without fetching every row to count them.
 */
public record PageResponse<T>(int page, int size, long total, List<T> items) {

    public static <T> PageResponse<T> of(PageRequest request, long total, List<T> items) {
        return new PageResponse<>(request.page(), request.size(), total, items);
    }
}
