package ro.myfinance.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailHistoryTest {

    @Test
    void providerStatusesAreMonotonicAndDuplicateEventsAreHarmless() {
        EmailHistory history = new EmailHistory(UUID.randomUUID(), EmailKind.TAX,
                MessageChannel.WHATSAPP, UUID.randomUUID(), LocalDate.of(2026, 10, 1),
                List.of(), "+40700000000", "message", EmailStatus.QUEUED, null, null);

        Instant deliveredAt = Instant.parse("2026-10-31T12:00:00Z");
        history.recordProviderMessage("wamid.1", deliveredAt);
        history.applyProviderStatus("delivered", deliveredAt);
        history.applyProviderStatus("sent", deliveredAt.plusSeconds(10));
        history.applyProviderStatus("delivered", deliveredAt.plusSeconds(20));

        assertThat(history.getProviderMessageId()).isEqualTo("wamid.1");
        assertThat(history.getProviderStatus()).isEqualTo("delivered");
        assertThat(history.getProviderStatusAt()).isEqualTo(deliveredAt);
        assertThat(history.getStatus()).isEqualTo(EmailStatus.SENT);
    }

    @Test
    void failureCannotMoveDeliveredMessageBackwards() {
        EmailHistory history = new EmailHistory(UUID.randomUUID(), EmailKind.TAX,
                MessageChannel.WHATSAPP, UUID.randomUUID(), LocalDate.of(2026, 10, 1),
                List.of(), "+40700000000", "message", EmailStatus.QUEUED, null, null);

        history.applyProviderStatus("delivered", Instant.now());
        history.applyProviderStatus("failed", Instant.now());

        assertThat(history.getProviderStatus()).isEqualTo("delivered");
        assertThat(history.getStatus()).isEqualTo(EmailStatus.SENT);
    }
}
