package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.application.AuthUserCleanup;
import ro.myfinance.access.application.UserInviter;
import ro.myfinance.common.outbox.OutboxMessage;
import ro.myfinance.common.outbox.OutboxWriter;

/** The compensation enqueues a durable DELETE_AUTH_USER outbox job, and delivery deletes the auth user. */
class AuthUserCleanupTest {

    private final UserInviter inviter = mock(UserInviter.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthUserCleanup cleanup = new AuthUserCleanup(inviter, outbox, mapper);

    @Test
    void scheduleDeleteEnqueuesADeleteAuthUserJob() {
        UUID externalId = UUID.randomUUID();

        cleanup.scheduleDelete(externalId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(AuthUserCleanup.AGGREGATE), eq(externalId.toString()),
                eq(AuthUserCleanup.TYPE), payload.capture());
        assertThat(payload.getValue()).contains(externalId.toString());
    }

    @Test
    void handleDeletesTheAuthUserFromThePayload() throws Exception {
        UUID externalId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new AuthUserCleanup.Payload(externalId));
        OutboxMessage msg = mock(OutboxMessage.class);
        when(msg.getPayload()).thenReturn(payload);

        cleanup.handle(msg);

        verify(inviter).delete(externalId);
    }

    @Test
    void typeIsDeleteAuthUser() {
        assertThat(cleanup.type()).isEqualTo("DELETE_AUTH_USER");
    }
}
