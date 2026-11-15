package ro.filegrid.policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Where policy files are read from: {@code classpath:/policies/} by default (inside the jar, so reloading is moot),
 * and in Docker a mounted directory given by {@code FILEGRID_POLICY_DIR} (e.g. {@code /app/policies}). All
 * {@code *.yml} and {@code *.yaml} files are read, in name order.
 */
@FunctionalInterface
public interface PolicySource {

    /** File name -> content. */
    Map<String, byte[]> read() throws IOException;

    /** A description for logs (the path). */
    default String description() {
        return toString();
    }

    /**
     * @param location {@code classpath:...}, {@code file:...} or a directory path (absolute or relative)
     */
    static PolicySource of(String location) {
        String base = toResourceLocation(location);
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return new PolicySource() {
            @Override
            public Map<String, byte[]> read() throws IOException {
                List<Resource> resources = new ArrayList<>();
                resources.addAll(Arrays.asList(resolver.getResources(base + "*.yml")));
                resources.addAll(Arrays.asList(resolver.getResources(base + "*.yaml")));
                resources.removeIf(r -> !r.exists() || r.getFilename() == null);
                resources.sort((a, b) -> a.getFilename().compareTo(b.getFilename()));
                Map<String, byte[]> files = new LinkedHashMap<>();
                for (Resource resource : resources) {
                    try (InputStream in = resource.getInputStream()) {
                        files.put(resource.getFilename(), in.readAllBytes());
                    }
                }
                return files;
            }

            @Override
            public String description() {
                return base;
            }
        };
    }

    private static String toResourceLocation(String location) {
        String value = location == null || location.isBlank() ? "classpath:/policies/" : location.trim();
        if (!value.startsWith("classpath:") && !value.startsWith("file:")) {
            value = Path.of(value).toAbsolutePath().toUri().toString();
        }
        return value.endsWith("/") ? value : value + "/";
    }
}
