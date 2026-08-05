package com.ecommercehub.connector;

/** A page request — 1-indexed, matching how every connector we'll ever add paginates. */
public record Page(int pageNumber, int pageSize) {
    public Page {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
    }

    public static Page first(int pageSize) {
        return new Page(1, pageSize);
    }

    public Page next() {
        return new Page(pageNumber + 1, pageSize);
    }
}
