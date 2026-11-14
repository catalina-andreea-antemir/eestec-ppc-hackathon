package ro.filegrid.storage.s3;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beanul {@link S3Adapter} (C5), găsit de {@code StorageAdapterRegistry} ca {@code StorageAdapter} și
 * {@code ChunkedWriter} pentru {@code S3}. Clientul S3 se închide odată cu contextul ({@code close()}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    S3Adapter s3Adapter(S3Properties properties) {
        return S3Adapter.fromProperties(properties);
    }
}
