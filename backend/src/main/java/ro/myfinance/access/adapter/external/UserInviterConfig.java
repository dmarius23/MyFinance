package ro.myfinance.access.adapter.external;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.access.application.UserInviter;

/**
 * Selects the representative-invite adapter at startup: the real Supabase adapter when a service-role
 * key + URL are configured, otherwise the logging fallback (local dev / tests). Using an explicit
 * factory (rather than @ConditionalOnProperty) so that a blank key correctly selects the fallback.
 */
@Configuration
public class UserInviterConfig {

    @Bean
    UserInviter representativeInviter(SupabaseProperties props, RestClient.Builder restClientBuilder) {
        return props.isConfigured()
                ? new SupabaseUserInviter(props, restClientBuilder)
                : new LoggingUserInviter();
    }
}
