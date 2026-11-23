package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.MatchSuggester;
import ro.myfinance.extraction.application.ReconciliationService.OpenInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.OpenTxnView;
import ro.myfinance.extraction.application.ReconciliationView;

/**
 * The defect this closes: amounts were compared within a tolerance and no currency check, so a EUR 59.90
 * invoice could be suggested against a RON 59.90 payment — at ~5 RON/EUR, two different payments.
 */
class MatchSuggesterCurrencyTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate PERIOD = LocalDate.of(2026, 9, 1);
    // Pairing is per-IBAN, so both sides must share one.
    private static final String IBAN = "RO49AAAA1B31007593840000";

    private OpenInvoiceView invoice(String amount, String currency) {
        BigDecimal v = new BigDecimal(amount);
        return new OpenInvoiceView(UUID.randomUUID(), UUID.randomUUID(), "f.pdf", "Bolt", IBAN, v,
                LocalDate.of(2026, 9, 24), PERIOD, BigDecimal.ZERO, v,
                false, Boolean.FALSE, null, "50109730", currency);
    }

    private OpenTxnView txn(String amount, String currency) {
        BigDecimal v = new BigDecimal(amount);
        return new OpenTxnView(UUID.randomUUID(), LocalDate.of(2026, 9, 25), v, "Bolt", IBAN,
                BigDecimal.ZERO, v, currency);
    }

    private List<?> suggest(List<OpenTxnView> txns, List<OpenInvoiceView> invs) {
        ReconciliationView view = mock(ReconciliationView.class);
        when(view.openTransactions(eq(COMPANY), eq(PERIOD), anyInt())).thenReturn(txns);
        when(view.openInvoices(eq(COMPANY), eq(PERIOD), anyInt())).thenReturn(invs);
        return new MatchSuggester(view).suggestions(COMPANY, PERIOD);
    }

    @Test
    void doesNotSuggestAEuroInvoiceForARonPaymentOfTheSameNumber() {
        assertThat(suggest(List.of(txn("59.90", "RON")), List.of(invoice("59.90", "EUR")))).isEmpty();
    }

    @Test
    void stillSuggestsWhenBothSidesAreTheSameCurrency() {
        assertThat(suggest(List.of(txn("59.90", "RON")), List.of(invoice("59.90", "RON")))).hasSize(1);
    }

    @Test
    void stillSuggestsWhenBothSidesAreEuro() {
        assertThat(suggest(List.of(txn("59.90", "EUR")), List.of(invoice("59.90", "EUR")))).hasSize(1);
    }

    @Test
    void doesNotCombineInvoicesOfDifferentCurrenciesToReachOnePayment() {
        // The subset-sum path: 40 EUR + 19.90 RON must not be offered as settling a 59.90 RON payment.
        assertThat(suggest(List.of(txn("59.90", "RON")),
                List.of(invoice("40.00", "EUR"), invoice("19.90", "RON")))).isEmpty();
    }

    @Test
    void stillCombinesTwoInvoicesOfTheSameCurrency() {
        // Guards the opposite error: filtering by currency must not disable the subset-sum path.
        assertThat(suggest(List.of(txn("59.90", "RON")),
                List.of(invoice("40.00", "RON"), invoice("19.90", "RON")))).hasSize(1);
    }

    @Test
    void doesNotCombineTransactionsOfDifferentCurrenciesToSettleOneInvoice() {
        // INSTALLMENT path: 40 RON + 19.90 EUR must not be offered as settling a 59.90 RON invoice.
        assertThat(suggest(List.of(txn("40.00", "RON"), txn("19.90", "EUR")),
                List.of(invoice("59.90", "RON")))).isEmpty();
    }

    @Test
    void stillCombinesTwoTransactionsOfTheSameCurrencyForOneInvoice() {
        // Guards the opposite error: filtering by currency must not disable the INSTALLMENT path.
        assertThat(suggest(List.of(txn("40.00", "RON"), txn("19.90", "RON")),
                List.of(invoice("59.90", "RON")))).hasSize(1);
    }
}
