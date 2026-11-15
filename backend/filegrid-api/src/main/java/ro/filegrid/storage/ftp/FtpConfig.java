package ro.filegrid.storage.ftp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link FtpAdapter} bean (C6), picked up by {@code StorageAdapterRegistry} as the {@code StorageAdapter} and
 * {@code ChunkedWriter} for {@code FTP}. The adapter holds no connections between operations, so there is nothing to close.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FtpProperties.class)
public class FtpConfig {

    @Bean
    FtpAdapter ftpAdapter(FtpProperties properties) {
        return FtpAdapter.fromProperties(properties);
    }
}
