package ro.myfinance.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed scheduler locking (S6). Scheduled jobs annotated with {@code @SchedulerLock} acquire a named
 * row-lock in the {@code shedlock} table so a job runs <b>exactly once per tick</b> even when several
 * web/worker instances are up — the losing instances simply skip the tick. Uses the admin JdbcTemplate
 * (RLS-bypassing) since the lock is cross-instance infrastructure, not tenant data, and {@code usingDbTime}
 * so lock timing relies on the database clock (immune to per-instance clock skew).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    LockProvider lockProvider(@Qualifier("adminJdbcTemplate") JdbcTemplate admin) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(admin)
                        .usingDbTime()
                        .build());
    }
}
