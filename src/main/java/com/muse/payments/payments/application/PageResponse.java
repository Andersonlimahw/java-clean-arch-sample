package com.muse.payments.payments.application;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements, String sort) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        return new PageResponse<>(items, page, size, totalElements, totalPages, sort);
    }
}
