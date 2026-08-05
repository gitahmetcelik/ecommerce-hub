package com.ecommercehub.connector;

import java.util.List;

public record PagedResult<T>(List<T> items, int page, int pageSize, int totalPages, boolean hasMore) {
}
