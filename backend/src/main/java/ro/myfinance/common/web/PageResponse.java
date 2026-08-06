package ro.myfinance.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable JSON envelope for a paged result. Spring's {@code PageImpl} serializes to an unstable,
 * deprecated shape, so controllers wrap a {@link Page} in this explicit record instead — the frontend
 * relies on {@code last}/{@code totalElements} to drive infinite scroll.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
                              boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}
