package ro.filegrid.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The two databases, with separate JDBC pools (per-category isolation; C9 tunes the sizes):
 * <ul>
 *   <li><b>FileGridMeta</b> ({@code spring.datasource.*}): the primary {@code DataSource} ({@link Primary}), used by
 *       Flyway, {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate} and the FileGridMeta repositories.</li>
 *   <li><b>FileGridStorage</b> ({@code filegrid.storage-db.*}, {@code STORAGE_DB_*} variables): the
 *       {@value #STORAGE_DATA_SOURCE} bean, only for {@code DbStorageAdapter} (O3), injected with
 *       {@code @Qualifier(DataSourceConfig.STORAGE_DATA_SOURCE)}. The pool opens lazily, so an OFFLINE
 *       FileGridStorage does not prevent the app from starting.</li>
 * </ul>
 * Do not declare any other {@code DataSource} beans: that would disable auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@Profile("db")
public class DataSourceConfig {

    public static final String STORAGE_DATA_SOURCE = "storageDataSource";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties metaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties metaDataSourceProperties) {
        return metaDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("filegrid.storage-db")
    DataSourceProperties storageDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(STORAGE_DATA_SOURCE)
    @ConfigurationProperties("filegrid.storage-db.hikari")
    HikariDataSource storageDataSource(
            @Qualifier("storageDataSourceProperties") DataSourceProperties storageDataSourceProperties) {
        return storageDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
