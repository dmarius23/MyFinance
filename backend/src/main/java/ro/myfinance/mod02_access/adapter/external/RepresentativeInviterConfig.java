package ro.myfinance.mod02_access.adapter.external;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.mod02_access.application.RepresentativeInviter;

/**
 * Selects the representative-invite adapter at startup: the real Supabase adapter when a service-role
 * key + URL are configured, otherwise the logging fallback (local dev / tests). Using an explicit
 * factory (rather than @ConditionalOnProperty) so that a blank key correctly selects the fallback.
 */
@Configuration
public class RepresentativeInviterConfig {

    @Bean
    RepresentativeInviter representativeInviter(SupabaseProperties props, RestClient.Builder restClientBuilder) {
        return props.isConfigured()
                ? new SupabaseRepresentativeInviter(props, restClientBuilder)
                : new LoggingRepresentativeInviter();
    }
}
