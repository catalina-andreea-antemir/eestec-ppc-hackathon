package ro.filegrid.ingest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import ro.filegrid.domain.Category;

/**
 * {@code GET /files} criteria. {@code categories} are those the role may see (empty = none); {@code category} is the
 * requested filter (optional); {@code from} is inclusive and {@code to} exclusive, on {@code createdAt}.
 */
public record FileSearch(Collection<Category> categories, Category category, String q, Instant from, Instant to,
                         int page, int size) {

    /** One page of results plus the total number of matches. */
    public record Result(List<FileRecord> items, long total) {
    }
}
