package ro.myfinance.common.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a Supabase JWT's {@code role} claim to a single Spring authority ({@code ROLE_*}).
 *
 * <p>The {@code tenant_id}, {@code role} and (for representatives) {@code company_id} claims are
 * expected to be injected into the access token by a Supabase
 * <a href="https://supabase.com/docs/guides/auth/auth-hooks">custom access token hook</a>.
 * Unknown/absent role → no authority, so the request is treated as authenticated-but-unauthorized.
 */
public class SupabaseJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        String raw = jwt.getClaimAsString("role");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return List.of(new SimpleGrantedAuthority(Role.valueOf(raw.trim().toUpperCase()).authority()));
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
