package ro.myfinance.common.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import ro.myfinance.common.security.RlsDataSource;

/**
 * Builds the application datasource (connecting as the RLS-subject {@code myfinance_app} role) and
 * wraps it in {@link RlsDataSource}. Flyway runs migrations through its own admin datasource
 * (see {@code spring.flyway.*}), so DDL is unaffected by RLS.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource hikari = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new RlsDataSource(hikari);
    }
}
