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

    /**
     * The pooled application datasource. {@code @ConfigurationProperties("spring.datasource.hikari")}
     * binds the pool tuning (size, name, timeouts, …) onto the built {@link HikariDataSource} —
     * {@code initializeDataSourceBuilder()} alone only carries the core connection props (url/user/
     * password/driver), so without this the {@code spring.datasource.hikari.*} yaml/env settings would
     * be silently ignored and the pool would run on Hikari defaults. Not {@code @Primary}: the wrapping
     * {@link RlsDataSource} is the primary {@link DataSource} the app injects.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource appHikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    DataSource dataSource(HikariDataSource appHikariDataSource) {
        return new RlsDataSource(appHikariDataSource);
    }

    /**
     * Small admin JdbcTemplate on the migration role (bypasses RLS). Used ONLY by background jobs that
     * must enumerate work across tenants (e.g. the ingestion scheduler) before running per-tenant under
     * the normal RLS datasource. Never use it to read/write tenant business data.
     */
    @Bean(name = "adminJdbcTemplate")
    org.springframework.jdbc.core.JdbcTemplate adminJdbcTemplate(
            @org.springframework.beans.factory.annotation.Value("${spring.flyway.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${spring.flyway.user}") String user,
            @org.springframework.beans.factory.annotation.Value("${spring.flyway.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(2);
        ds.setPoolName("myfinance-admin-pool");
        return new org.springframework.jdbc.core.JdbcTemplate(ds);
    }
}
