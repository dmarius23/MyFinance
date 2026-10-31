package ro.myfinance.common.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;

class WhatsAppMessageTest {

    @Test
    void providerLifecycleIsMonotonic() {
        WhatsAppMessage message = new WhatsAppMessage(UUID.randomUUID(), EmailKind.TAX,
                UUID.randomUUID(), LocalDate.of(2026, 10, 1), List.of(), List.of(),
                "+40700000000", "body", EmailStatus.QUEUED, null, "key", "hash",
                "tax_notice", "ro_RO", "{}");
        Instant deliveredAt = Instant.parse("2026-10-31T12:00:00Z");

        message.recordProviderMessage("wamid.1", deliveredAt);
        message.applyProviderStatus("delivered", deliveredAt);
        message.applyProviderStatus("sent", deliveredAt.plusSeconds(10));

        assertThat(message.getProviderMessageId()).isEqualTo("wamid.1");
        assertThat(message.getProviderStatus()).isEqualTo("delivered");
        assertThat(message.getProviderStatusAt()).isEqualTo(deliveredAt);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
    }
}
