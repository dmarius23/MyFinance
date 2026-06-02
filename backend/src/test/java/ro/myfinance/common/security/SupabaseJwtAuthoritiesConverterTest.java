package ro.myfinance.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseJwtAuthoritiesConverterTest {

    private final SupabaseJwtAuthoritiesConverter converter = new SupabaseJwtAuthoritiesConverter();

    @Test
    void mapsKnownRoleToAuthority() {
        var authorities = converter.convert(jwtWithRole("TENANT_ADMIN"));
        assertThat(authorities).extracting("authority").containsExactly("ROLE_TENANT_ADMIN");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(converter.convert(jwtWithRole("employee")))
                .extracting("authority").containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    void returnsNoAuthorityForUnknownRole() {
        assertThat(converter.convert(jwtWithRole("WIZARD"))).isEmpty();
    }

    @Test
    void returnsNoAuthorityWhenRoleClaimMissing() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("u").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRole(String role) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-0000000000a1")
                .claim("role", role)
                .claim("tenant_id", "00000000-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(c -> c.putAll(Map.of()))
                .build();
    }
}
