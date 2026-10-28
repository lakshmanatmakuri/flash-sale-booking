package com.example.booking.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Our own stable page shape. We never serialize Spring's PageImpl directly (its JSON is not a stable contract). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}