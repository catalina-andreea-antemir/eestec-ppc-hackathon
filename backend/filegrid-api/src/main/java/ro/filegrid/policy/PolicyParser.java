package ro.filegrid.policy;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import ro.filegrid.domain.Category;
import ro.filegrid.domain.StorageType;

/**
 * Turns the content of {@code policies/*.yml} (one file per category) into validated policies. Unknown fields are
 * errors, so a typo in the YAML cannot go unnoticed.
 */
final class PolicyParser {

    static final Set<String> FIELDS = Set.of("category", "primaryStorage", "fallbackStorage", "maxSize",
            "encryption", "chunkSize", "maxConcurrentRequests");

    private PolicyParser() {
    }

    /**
     * @param files file name -> content (UTF-8)
     * @return each category's policy; all 4 categories are present, each exactly once
     * @throws PolicyException on any invalid file or field
     */
    static Map<Category, CategoryPolicy> parse(Map<String, byte[]> files) {
        Map<Category, CategoryPolicy> policies = new EnumMap<>(Category.class);
        Map<Category, String> sources = new EnumMap<>(Category.class);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            CategoryPolicy policy = parseFile(file.getKey(), new String(file.getValue(), StandardCharsets.UTF_8));
            String previous = sources.putIfAbsent(policy.category(), file.getKey());
            if (previous != null) {
                throw new PolicyException("Categoria " + policy.category() + " apare în două fișiere: " + previous
                        + " și " + file.getKey());
            }
            policies.put(policy.category(), policy);
        }
        for (Category category : Category.values()) {
            if (!policies.containsKey(category)) {
                throw new PolicyException("Lipsește politica pentru categoria " + category);
            }
        }
        return policies;
    }

    static CategoryPolicy parseFile(String fileName, String content) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (RuntimeException e) {
            throw new PolicyException(fileName + ": YAML invalid (" + e.getMessage() + ")", e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new PolicyException(fileName + ": se aștepta un obiect YAML cu câmpurile " + FIELDS);
        }
        for (Object key : map.keySet()) {
            if (!FIELDS.contains(String.valueOf(key))) {
                throw new PolicyException(fileName + ": câmp necunoscut „" + key + "” (permise: " + FIELDS + ")");
            }
        }
        try {
            return new CategoryPolicy(
                    enumValue(Category.class, required(map, "category")),
                    enumValue(StorageType.class, required(map, "primaryStorage")),
                    enumValue(StorageType.class, required(map, "fallbackStorage")),
                    DataSizes.parse(required(map, "maxSize")),
                    booleanValue(required(map, "encryption")),
                    chunkSize(map.get("chunkSize")),
                    intValue(required(map, "maxConcurrentRequests")));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new PolicyException(fileName + ": " + e.getMessage(), e);
        }
    }

    private static Object required(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (value == null) {
            throw new IllegalArgumentException("câmpul „" + field + "” este obligatoriu");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("valoare necunoscută „" + value + "” pentru " + type.getSimpleName());
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("encryption este true sau false, nu „" + value + "”");
    }

    private static int intValue(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException("maxConcurrentRequests este un număr întreg, nu „" + value + "”");
    }

    private static Integer chunkSize(Object value) {
        if (value == null) {
            return null;
        }
        long bytes = DataSizes.parse(value);
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("chunkSize trebuie să fie între 5 MiB și 64 MiB");
        }
        return (int) bytes;
    }
}
