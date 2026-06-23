package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.common.security.Role;
import ro.myfinance.access.adapter.external.SupabaseUserInviter;
import ro.myfinance.access.application.UserInviter.InviteClaims;

class SupabaseUserInviterTest {

    @Test
    void invitesViaGoTrueAndSetsAppMetadata() {
        UUID newUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://proj.supabase.co/auth/v1/invite"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-key"))
                .andExpect(header("apikey", "test-service-key"))
                .andExpect(jsonPath("$.email").value("rep@client.ro"))
                .andRespond(withSuccess("{\"id\":\"" + newUserId + "\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://proj.supabase.co/auth/v1/admin/users/" + newUserId))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.app_metadata.role").value("REPRESENTATIVE"))
                .andExpect(jsonPath("$.app_metadata.company_id").exists())
                .andExpect(jsonPath("$.app_metadata.tenant_id").exists())
                .andRespond(withSuccess("{\"id\":\"" + newUserId + "\"}", MediaType.APPLICATION_JSON));

        var props = new SupabaseProperties("https://proj.supabase.co", "test-service-key");
        var inviter = new SupabaseUserInviter(props, builder);

        var result = inviter.invite("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));

        assertThat(result.externalUserId()).isEqualTo(newUserId);
        server.verify();
    }
}
