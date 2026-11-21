package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import ro.myfinance.ingestion.application.IngestionScheduler;

/**
 * The nightly Drive poll is switched on by an environment variable and by nothing else: it is off in
 * application.yml, and {@link IngestionScheduler} is {@code @ConditionalOnProperty}, so if the variable
 * name and the property name ever drift apart the scheduler bean is simply never created — no error, no
 * log line, just a deployment that quietly stops importing anything nobody syncs by hand. That is the
 * failure this pins down: both ends of the switch, asserted against the literal name the deployment sets
 * ({@code deploy/env.prod.example}, synced by {@code .github/workflows/deploy.yml}).
 */
class IngestionPollFlagTest {

    /** The variable name written into the production .env. Changing it here means changing it there. */
    private static final String ENV_VAR = "MYFINANCE_INGESTION_POLL_ENABLED";

    @Test
    void theEnvironmentVariableTheDeploymentSetsResolvesToThePropertyTheSchedulerReads() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(ENV_VAR, "true")));

        ConditionalOnProperty condition = IngestionScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as("the poll must stay opt-in").isNotNull();

        String property = condition.prefix() + "." + condition.name()[0];
        assertThat(property).isEqualTo("myfinance.ingestion.poll.enabled");
        assertThat(env.getProperty(property))
                .as("%s must switch on %s", ENV_VAR, property)
                .isEqualTo(condition.havingValue());
    }

    @Test
    void withoutThatVariableTheSchedulerStaysOff() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));

        ConditionalOnProperty condition = IngestionScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(env.getProperty(condition.prefix() + "." + condition.name()[0])).isNull();
        assertThat(condition.matchIfMissing()).as("absent must mean off").isFalse();
    }
}
