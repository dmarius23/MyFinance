package ro.myfinance.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Locks in the {@code spring.datasource.hikari.*} binding. Before the two-bean split in
 * {@link DataSourceConfig}, {@code initializeDataSourceBuilder()} carried only the core connection
 * props, so the pool name and size were silently ignored and Hikari ran on its defaults. This proves
 * both a yaml-declared setting (pool-name) and an overridden one (maximum-pool-size) reach the pool.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=7")
class HikariPoolConfigIT extends AbstractPostgresIT {

    @Autowired
    HikariDataSource appHikariDataSource;

    @Test
    void bindsHikariPoolPropertiesOntoTheDataSource() {
        // From application.yml — proves declared hikari settings are applied, not dropped.
        assertThat(appHikariDataSource.getPoolName()).isEqualTo("myfinance-app-pool");
        // From @TestPropertySource — proves an override actually reaches the pool (was stuck at default).
        assertThat(appHikariDataSource.getMaximumPoolSize()).isEqualTo(7);
    }
}
