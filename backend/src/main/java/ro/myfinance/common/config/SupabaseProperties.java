package ro.myfinance.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase server-side config. {@code serviceRoleKey} is a secret (env only) and gates the real
 * invite adapter; when blank, the logging fallback is used. {@code issuer} is the project's
 * {@code .../auth/v1} URL, pinned by the JWT decoder so a validly-signed token from a different
 * Supabase project is rejected.
 */
@ConfigurationProperties(prefix = "myfinance.supabase")
public record SupabaseProperties(String url, String serviceRoleKey, String issuer) {

    public SupabaseProperties {
        url = url == null ? "" : url;
        serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
        issuer = issuer == null ? "" : issuer;
    }

    public boolean isConfigured() {
        return !serviceRoleKey.isBlank() && !url.isBlank();
    }
}
