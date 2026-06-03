package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.mod02_access.adapter.external.LoggingRepresentativeInviter;
import ro.myfinance.mod02_access.application.RepresentativeInviter.InviteClaims;

class LoggingRepresentativeInviterTest {

    @Test
    void returnsAGeneratedExternalUserId() {
        var inviter = new LoggingRepresentativeInviter();
        var result = inviter.invite("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));
        assertThat(result.externalUserId()).isNotNull();
    }
}
