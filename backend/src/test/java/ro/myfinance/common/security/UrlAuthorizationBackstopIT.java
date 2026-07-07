package ro.myfinance.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Verifies the filter-layer role backstop (S1): role enforcement happens in the security filter chain
 * regardless of any controller {@code @PreAuthorize}. A wrong-role token is rejected with 403 before the
 * controller runs; a right-role token passes the filter (whatever status the controller then returns, it is
 * not a 403 from the filter). This is the fail-safe-defaults guarantee for a controller that ever forgets
 * its annotation.
 */
@AutoConfigureMockMvc
class UrlAuthorizationBackstopIT extends AbstractPostgresIT {

    private static final String STAFF_ROUTE = "/api/v1/companies/" + UUID.randomUUID() + "/representatives";
    private static final String PORTAL_ROUTE = "/api/v1/portal/me";
    private static final String ADMIN_ROUTE = "/api/v1/admin/tenants";

    @Autowired MockMvc mvc;

    private static RequestPostProcessor token(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", UUID.randomUUID().toString())
                        .claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void representativeIsBlockedFromStaffRoute() throws Exception {
        mvc.perform(get(STAFF_ROUTE).with(token("REPRESENTATIVE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void representativeIsBlockedFromAdminRoute() throws Exception {
        mvc.perform(get(ADMIN_ROUTE).with(token("REPRESENTATIVE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffIsBlockedFromPortalRoute() throws Exception {
        mvc.perform(get(PORTAL_ROUTE).with(token("TENANT_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffIsBlockedFromAdminRoute() throws Exception {
        mvc.perform(get(ADMIN_ROUTE).with(token("TENANT_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffPassesTheFilterOnStaffRoute() throws Exception {
        mvc.perform(get(STAFF_ROUTE).with(token("TENANT_ADMIN")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    void representativePassesTheFilterOnPortalRoute() throws Exception {
        mvc.perform(get(PORTAL_ROUTE).with(token("REPRESENTATIVE")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    @Test
    void superAdminPassesTheFilterOnAdminRoute() throws Exception {
        mvc.perform(get(ADMIN_ROUTE).with(token("SUPER_ADMIN")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }
}
