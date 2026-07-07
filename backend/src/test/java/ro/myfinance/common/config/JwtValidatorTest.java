package ro.myfinance.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Verifies the JWT validator (S3): the token issuer is pinned and an explicit clock skew is applied on top
 * of signature validation. Exercises the validator directly with hand-built {@link Jwt}s so no live JWKS
 * endpoint is needed (signature is validated separately by the Nimbus decoder).
 */
class JwtValidatorTest {

    private static final String ISSUER = "https://my-project.supabase.co/auth/v1";
    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(ISSUER);

    private static Jwt.Builder jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .issuedAt(now.minus(1, ChronoUnit.MINUTES))
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("sub", "user");
    }

    @Test
    void acceptsTokenFromExpectedIssuer() {
        OAuth2TokenValidatorResult result = validator.validate(jwt().issuer(ISSUER).build());
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenFromDifferentIssuer() {
        Jwt other = jwt().issuer("https://someone-elses-project.supabase.co/auth/v1").build();
        assertThat(validator.validate(other).hasErrors()).isTrue();
    }

    @Test
    void rejectsExpiredTokenBeyondSkew() {
        Instant now = Instant.now();
        Jwt expired = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .issuer(ISSUER)
                .issuedAt(now.minus(2, ChronoUnit.HOURS))
                .expiresAt(now.minus(5, ChronoUnit.MINUTES)) // well past the 30s skew
                .claim("sub", "user")
                .build();
        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }

    @Test
    void derivesIssuerFromJwksUriWhenNotConfigured() {
        String jwks = "https://my-project.supabase.co/auth/v1/.well-known/jwks.json";
        assertThat(SecurityConfig.effectiveIssuer("", jwks)).isEqualTo(ISSUER);
        assertThat(SecurityConfig.effectiveIssuer(null, jwks)).isEqualTo(ISSUER);
    }

    @Test
    void prefersExplicitlyConfiguredIssuer() {
        assertThat(SecurityConfig.effectiveIssuer(ISSUER, "https://other.supabase.co/auth/v1/.well-known/jwks.json"))
                .isEqualTo(ISSUER);
    }

    @Test
    void rejectsTokenWithNoIssuer() {
        Jwt noIssuer = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .claim("sub", "user")
                .issuedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        assertThat(validator.validate(noIssuer).hasErrors()).isTrue();
    }
}
