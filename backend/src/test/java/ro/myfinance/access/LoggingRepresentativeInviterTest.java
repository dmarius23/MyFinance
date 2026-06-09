package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.access.adapter.external.LoggingRepresentativeInviter;
import ro.myfinance.access.application.RepresentativeInviter.InviteClaims;

class LoggingRepresentativeInviterTest {

    @Test
    void returnsAGeneratedExternalUserId() {
        var inviter = new LoggingRepresentativeInviter();
        var result = inviter.invite("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));
        assertThat(result.externalUserId()).isNotNull();
    }
}
