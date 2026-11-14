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
 * Găsește adaptorul și writer-ul pentru un {@link StorageType}, din beanurile Spring {@link StorageAdapter} și
 * {@link ChunkedWriter}. Două beanuri pentru același storage opresc pornirea; un storage fără implementare încă (de
 * exemplu înainte de O3) se semnalează abia la folosire, cu {@link StorageUnavailableException}.
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
        log.info("Storage-uri disponibile: adaptoare {}, upload în bucăți {}", this.adapters.keySet(),
                this.writers.keySet());
    }

    /** @throws StorageUnavailableException dacă nu există încă o implementare pentru acest storage */
    public StorageAdapter adapter(StorageType type) {
        return find(type).orElseThrow(() ->
                new StorageUnavailableException(type, "Nu există adaptor pentru storage-ul " + type));
    }

    public Optional<StorageAdapter> find(StorageType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    /** @throws StorageUnavailableException dacă storage-ul nu are încă upload în bucăți */
    public ChunkedWriter chunkedWriter(StorageType type) {
        ChunkedWriter writer = writers.get(type);
        if (writer == null) {
            throw new StorageUnavailableException(type, "Storage-ul " + type + " nu are încă upload în bucăți");
        }
        return writer;
    }

    /** Storage-urile care au adaptor. */
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
