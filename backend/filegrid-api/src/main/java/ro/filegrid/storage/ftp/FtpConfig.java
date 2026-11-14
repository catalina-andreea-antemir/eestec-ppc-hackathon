package ro.filegrid.storage.ftp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beanul {@link FtpAdapter} (C6), găsit de {@code StorageAdapterRegistry} ca {@code StorageAdapter} și
 * {@code ChunkedWriter} pentru {@code FTP}. Adaptorul nu ține conexiuni deschise între operații, deci nu are ce închide.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FtpProperties.class)
public class FtpConfig {

    @Bean
    FtpAdapter ftpAdapter(FtpProperties properties) {
        return FtpAdapter.fromProperties(properties);
    }
}
