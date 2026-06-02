package ro.myfinance.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After the bearer-token filter has authenticated the request, binds a {@link TenantContext}
 * from the JWT claims so downstream DB access is tenant-scoped. Always cleared in {@code finally}
 * to avoid leaking identity across pooled threads.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                TenantContext.set(new TenantContext.Identity(
                        parseUuid(jwt.getClaimAsString("tenant_id")),
                        parseUuid(jwt.getSubject()),
                        parseRole(jwt.getClaimAsString("role")),
                        parseUuid(jwt.getClaimAsString("company_id"))));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Role parseRole(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
