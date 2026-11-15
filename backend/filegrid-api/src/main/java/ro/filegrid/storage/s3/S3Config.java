package ro.filegrid.storage.s3;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link S3Adapter} bean (C5), picked up by {@code StorageAdapterRegistry} as the {@code StorageAdapter} and
 * {@code ChunkedWriter} for {@code S3}. The S3 client is closed together with the context ({@code close()}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    S3Adapter s3Adapter(S3Properties properties) {
        return S3Adapter.fromProperties(properties);
    }
}
