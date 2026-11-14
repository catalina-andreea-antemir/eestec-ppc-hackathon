package ro.filegrid.download;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DownloadProperties.class)
class DownloadConfig {
}
