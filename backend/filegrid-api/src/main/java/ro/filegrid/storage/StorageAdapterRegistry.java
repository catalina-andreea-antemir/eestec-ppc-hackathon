package ro.filegrid.storage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ro.filegrid.domain.StorageType;
import ro.filegrid.resilience.StorageGuard;

/**
 * Finds the adapter and writer for a {@link StorageType} among the Spring {@link StorageAdapter} and
 * {@link ChunkedWriter} beans. Two beans for the same storage stop startup; a storage without an implementation is
 * only reported when used, with {@link StorageUnavailableException}.
 */
@Component
public class StorageAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(StorageAdapterRegistry.class);

    private final Map<StorageType, StorageAdapter> adapters;
    private final Map<StorageType, ChunkedWriter> writers;

    @Autowired
    public StorageAdapterRegistry(ObjectProvider<StorageAdapter> adapters, ObjectProvider<ChunkedWriter> writers,
                                  StorageGuard guard) {
        this(adapters.orderedStream().map(guard::wrap).toList(), writers.orderedStream().map(guard::wrap).toList());
    }

    public StorageAdapterRegistry(List<StorageAdapter> adapters, List<ChunkedWriter> writers) {
        this.adapters = index(adapters, StorageAdapter::type, "StorageAdapter");
        this.writers = index(writers, ChunkedWriter::type, "ChunkedWriter");
        log.info("Available storage: adapters {}, chunked upload {}", this.adapters.keySet(),
                this.writers.keySet());
    }

    /** @throws StorageUnavailableException if there is no implementation for this storage */
    public StorageAdapter adapter(StorageType type) {
        return find(type).orElseThrow(() ->
                new StorageUnavailableException(type, "Nu există adaptor pentru storage-ul " + type));
    }

    public Optional<StorageAdapter> find(StorageType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    /** @throws StorageUnavailableException if the storage has no chunked upload support */
    public ChunkedWriter chunkedWriter(StorageType type) {
        ChunkedWriter writer = writers.get(type);
        if (writer == null) {
            throw new StorageUnavailableException(type, "Storage-ul " + type + " nu are încă upload în bucăți");
        }
        return writer;
    }

    /** Storage backends that have an adapter. */
    public Set<StorageType> availableTypes() {
        return Collections.unmodifiableSet(adapters.keySet());
    }

    private static <T> Map<StorageType, T> index(List<T> beans, Function<T, StorageType> type, String kind) {
        Map<StorageType, T> byType = new EnumMap<>(StorageType.class);
        for (T bean : beans) {
            T previous = byType.putIfAbsent(type.apply(bean), bean);
            if (previous != null) {
                throw new IllegalStateException("Două beanuri " + kind + " pentru storage-ul " + type.apply(bean)
                        + ": " + previous.getClass().getName() + " și " + bean.getClass().getName());
            }
        }
        return Collections.unmodifiableMap(byType);
    }
}
