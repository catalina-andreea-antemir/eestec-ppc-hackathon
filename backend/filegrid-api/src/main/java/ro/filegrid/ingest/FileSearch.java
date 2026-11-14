package ro.filegrid.ingest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import ro.filegrid.domain.Category;

/**
 * Criteriile {@code GET /files}. {@code categories} sunt cele permise rolului (gol = niciuna); {@code category} e
 * filtrul cerut (poate lipsi); {@code from} e inclusiv, {@code to} exclusiv, după {@code createdAt}.
 */
public record FileSearch(Collection<Category> categories, Category category, String q, Instant from, Instant to,
                         int page, int size) {

    /** O pagină de rezultate și totalul care se potrivește. */
    public record Result(List<FileRecord> items, long total) {
    }
}
