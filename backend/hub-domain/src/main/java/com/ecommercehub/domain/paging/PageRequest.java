package com.ecommercehub.domain.paging;

/**
 * Plan v5 §7.2 point 5: the shared shape every internal list query takes a page
 * through, replacing each list's own hardcoded {@code LIMIT 200}. {@code page} is
 * zero-based so it drops straight into a SQL {@code OFFSET}.
 */
public record PageRequest(int page, int size) {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }

    public static PageRequest of(Integer page, Integer size) {
        return new PageRequest(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    public int offset() {
        return page * size;
    }
}
