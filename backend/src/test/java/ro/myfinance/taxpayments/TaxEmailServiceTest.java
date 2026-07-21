package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.taxpayments.application.TaxEmailService;
import ro.myfinance.taxpayments.application.TaxPaymentService;

/**
 * TaxEmailService delegates the send to the shared {@link EmailDispatchService} with kind=TAX, the chosen
 * declaration ids as related ids, and no attachments — the SENT/FAILED recording itself is covered by
 * {@code EmailDispatchServiceTest}.
 */
class TaxEmailServiceTest {

    private final TaxPaymentService payments = mock(TaxPaymentService.class);
    private final EmailHistoryRepository history = mock(EmailHistoryRepository.class);
    private final EmailDispatchService dispatch = mock(EmailDispatchService.class);
    private final ro.myfinance.notifications.application.NotificationService notifications =
            mock(ro.myfinance.notifications.application.NotificationService.class);
    private final TaxEmailService service = new TaxEmailService(payments, history, dispatch, notifications);

    @Test
    @SuppressWarnings("unchecked")
    void sendDelegatesToDispatchWithTaxKindAndDeclarationIds() {
        UUID companyId = UUID.randomUUID();
        List<UUID> declIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        EmailHistory row = mock(EmailHistory.class);
        when(dispatch.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(row);

        var result = service.send(companyId, LocalDate.of(2026, 3, 10), declIds, "client@example.com", "body");

        assertThat(result).isSameAs(row);
        ArgumentCaptor<List<UUID>> related = ArgumentCaptor.forClass(List.class);
        verify(dispatch).dispatch(eq(EmailKind.TAX), eq(companyId), eq(LocalDate.of(2026, 3, 10)),
                eq("client@example.com"), any(String.class), eq("body"), isNull(), related.capture(), any());
        assertThat(related.getValue()).isEqualTo(declIds);
    }
}
