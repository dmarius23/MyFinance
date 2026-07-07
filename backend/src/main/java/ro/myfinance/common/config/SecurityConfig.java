package ro.myfinance.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ro.myfinance.common.security.SupabaseJwtAuthoritiesConverter;
import ro.myfinance.common.security.TenantContextFilter;

/**
 * Stateless resource-server security. The app validates Supabase-issued JWTs (JWKS configured via
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}); it never issues tokens itself.
 * After authentication, {@link TenantContextFilter} binds the tenant identity for RLS.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // Resolves the bean named "corsConfigurationSource" by name, avoiding ambiguity with
            // Spring MVC's mvcHandlerMappingIntrospector (which also exposes a CorsConfigurationSource).
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                        "/actuator/health", "/actuator/health/**", "/actuator/info",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .permitAll()
                // Fail-safe role backstop at the filter layer, in addition to per-method @PreAuthorize:
                // a controller that forgets its annotation still cannot expose a surface to the wrong role.
                // Order matters (first match wins) — specific prefixes precede the generic /api/v1/**.
                // The staff floor is coarse (TENANT_ADMIN vs EMPLOYEE stays with @PreAuthorize); SUPER_ADMIN
                // is allowed through the filter here but is still gated by @PreAuthorize on staff methods.
                .requestMatchers("/api/v1/portal/**").hasRole("REPRESENTATIVE")
                .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/v1/**").hasAnyRole("TENANT_ADMIN", "EMPLOYEE", "SUPER_ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new SupabaseJwtAuthoritiesConverter());
        return converter;
    }

    /**
     * Replaces Boot's auto-configured decoder to add issuer pinning and an explicit clock skew on top of
     * JWKS signature validation. Without this, any validly-signed Supabase token (regardless of which
     * project minted it) would be accepted on signature + expiry alone.
     */
    @Bean
    JwtDecoder jwtDecoder(SupabaseProperties supabase,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        // Supabase signs with ES256; re-declare both algorithms since withJwkSetUri defaults to RS256 only.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.RS256);
                })
                .build();
        decoder.setJwtValidator(jwtValidator(effectiveIssuer(supabase.issuer(), jwkSetUri)));
        return decoder;
    }

    /**
     * The issuer to pin. Prefer the explicit {@code myfinance.supabase.issuer}; when blank, derive it from
     * the JWKS URI (Supabase's {@code iss} is the JWKS URL minus {@code /.well-known/jwks.json}). This keeps
     * a single source of truth — configuring only {@code SUPABASE_JWKS_URI} is enough — while still failing
     * closed: with the {@code YOUR-PROJECT} placeholder JWKS URI the derived issuer is a placeholder too.
     */
    static String effectiveIssuer(String configuredIssuer, String jwkSetUri) {
        if (configuredIssuer != null && !configuredIssuer.isBlank()) {
            return configuredIssuer;
        }
        String suffix = "/.well-known/jwks.json";
        return jwkSetUri.endsWith(suffix)
                ? jwkSetUri.substring(0, jwkSetUri.length() - suffix.length())
                : jwkSetUri;
    }

    /** Issuer pinning + 30s clock skew; extracted for unit testing without a live JWKS endpoint. */
    static OAuth2TokenValidator<Jwt> jwtValidator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
