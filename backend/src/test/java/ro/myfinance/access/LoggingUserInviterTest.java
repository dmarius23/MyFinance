package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.access.adapter.external.LoggingUserInviter;
import ro.myfinance.access.application.UserInviter.InviteClaims;

class LoggingUserInviterTest {

    @Test
    void returnsAGeneratedExternalUserId() {
        var inviter = new LoggingUserInviter();
        var result = inviter.provision("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));
        assertThat(result.externalUserId()).isNotNull();
    }

    @Test
    void mintsARecoveryStyleSetPasswordLink() {
        assertThat(new LoggingUserInviter().generateSetPasswordLink("rep@client.ro")).contains("rep@client.ro");
    }
}
