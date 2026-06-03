package ro.myfinance.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase server-side config. {@code serviceRoleKey} is a secret (env only) and gates the real
 * invite adapter; when blank, the logging fallback is used.
 */
@ConfigurationProperties(prefix = "myfinance.supabase")
public record SupabaseProperties(String url, String serviceRoleKey) {

    public SupabaseProperties {
        url = url == null ? "" : url;
        serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
    }

    public boolean isConfigured() {
        return !serviceRoleKey.isBlank() && !url.isBlank();
    }
}
