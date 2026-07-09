package vn.essvn.erpcafe.common.web;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable paged-response envelope, decoupled from Spring Data's {@code Page}
 * internals so the client contract stays constant.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
