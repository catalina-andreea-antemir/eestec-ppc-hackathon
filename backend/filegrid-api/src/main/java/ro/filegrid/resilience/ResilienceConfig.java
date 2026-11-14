package ro.filegrid.resilience;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfig {
}
