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
 * Cele două baze, cu pool-uri JDBC separate (planul v4 4.1 „Izolare per categorie”; C9 reglează dimensiunile):
 * <ul>
 *   <li><b>FileGridMeta</b> ({@code spring.datasource.*}): {@code DataSource} principal ({@link Primary}), folosit de
 *       Flyway, {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate} și repository-urile din FileGridMeta.</li>
 *   <li><b>FileGridStorage</b> ({@code filegrid.storage-db.*}, variabilele {@code STORAGE_DB_*}): beanul
 *       {@value #STORAGE_DATA_SOURCE}, doar pentru {@code DbStorageAdapter} (O3), injectat cu
 *       {@code @Qualifier(DataSourceConfig.STORAGE_DATA_SOURCE)}. Pool-ul se deschide la prima folosire, deci
 *       FileGridStorage OFFLINE nu oprește pornirea aplicației.</li>
 * </ul>
 * Nu se mai declară alte beanuri {@code DataSource}: ar dezactiva configurarea automată.
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
