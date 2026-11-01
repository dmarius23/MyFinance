package ro.myfinance.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.support.AbstractPostgresIT;

/** The backend-log download is TENANT_ADMIN-only and serves the active log file as a text attachment. */
@AutoConfigureMockMvc
class LogDownloadControllerIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("dddddddd-0000-0000-0000-0000000000f4");

    @Autowired MockMvc mvc;

    private static RequestPostProcessor staff(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString()).claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void tenantAdminDownloadsTheLogFile() throws Exception {
        mvc.perform(get("/api/v1/settings/logs").with(staff("TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }

    @Test
    void employeeIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/settings/logs").with(staff("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/settings/logs")).andExpect(status().isUnauthorized());
    }
}
