package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateRepository;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.application.ReconciliationService.MatchedInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.TxnWithMatches;
import ro.myfinance.extraction.application.ReminderBodyService;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;

/**
 * The three reminder states and the rendered body for each. State selection is the server's job now —
 * the browser used to decide it, which is how the backend notification and the client email drifted apart.
 */
class ReminderStateSelectionTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANY = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private ReminderBodyService service;
    private DocumentService documents;
    private ReconciliationService reconciliation;
    private EmailEnvelopeService envelopes;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        MessageTemplateRepository repo = mock(MessageTemplateRepository.class);
        when(repo.findByTenantIdAndKind(any(), any())).thenReturn(Optional.empty());

        documents = mock(DocumentService.class);
        reconciliation = mock(ReconciliationService.class);
        envelopes = mock(EmailEnvelopeService.class);
        CompanyRepository companies = mock(CompanyRepository.class);

        when(envelopes.currentUserName()).thenReturn("Maria Popescu");
        when(envelopes.firmName()).thenReturn("ContaZone SRL");
        when(companies.findById(any())).thenReturn(Optional.empty());

        MessageTemplateService templateService = new MessageTemplateService(repo, new TemplateRenderer());
        service = new ReminderBodyService(documents, reconciliation, envelopes, templateService, companies);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void noStatementYieldsTheNothingReceivedState() {
        givenNoBankStatement();
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_NOTHING_RECEIVED);
        assertThat(msg.body()).contains("extrasul de cont");
        assertThat(msg.subject()).isEqualTo("Documente lipsă — Aprilie 2026");
        // No statement → transaction query must NOT run (Finding 1 fix: wasted query removed).
        verify(reconciliation, never()).transactionsWithMatches(any(), any());
    }

    @Test
    void statementPlusMissingDocsYieldsTheDocsMissingStateWithTheList() {
        givenBankStatement();
        givenTransactions(txnMissingDoc(LocalDate.of(2026, 4, 15), "Acme SRL", new BigDecimal("-1234.56")));
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_DOCS_MISSING);
        assertThat(msg.body()).contains("• 15.04.2026 — Acme SRL — 1.234,56 RON");
    }

    @Test
    void statementWithNothingMissingYieldsTheDocsOnlyState() {
        givenBankStatement();
        givenTransactions(); // none requiring a document
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
        assertThat(msg.body())
                .contains("facturile / chitanțele")
                .doesNotContain("extrasul de cont");
    }

    @Test
    void aTransactionThatAlreadyHasAnInvoiceIsNotListed() {
        givenBankStatement();
        givenTransactions(txnWithInvoice(LocalDate.of(2026, 4, 15), "Acme SRL", new BigDecimal("-100")));
        assertThat(service.compose(COMPANY, APRIL).state())
                .isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
    }

    @Test
    void aTransactionThatDoesNotRequireADocumentIsNotListed() {
        givenBankStatement();
        givenTransactions(txnNotRequiringDoc(LocalDate.of(2026, 4, 15), "Banca", new BigDecimal("-9")));
        assertThat(service.compose(COMPANY, APRIL).state())
                .isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
    }

    @Test
    void anUnknownPartnerFallsBackToTheDescriptionThenADash() {
        givenBankStatement();
        givenTransactions(txnMissingDocWithoutPartner(LocalDate.of(2026, 4, 2), "POS 1234",
                new BigDecimal("-50")));
        assertThat(service.compose(COMPANY, APRIL).body()).contains("• 02.04.2026 — POS 1234 — 50,00 RON");
    }

    /** Review Focus 5: a busy month must render completely — no truncation, no quadratic blow-up. */
    @Test
    void rendersTwoHundredMissingTransactionsInFull() {
        givenBankStatement();
        List<TxnWithMatches> many = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            many.add(txnMissingDoc(LocalDate.of(2026, 4, 1).plusDays(i % 28),
                    "Partener " + i, new BigDecimal("-100.00")));
        }
        givenTransactionList(many);
        String body = service.compose(COMPANY, APRIL).body();
        assertThat(body.lines().filter(l -> l.startsWith("• ")).count()).isEqualTo(200);
        assertThat(body).contains("Partener 200").endsWith("ContaZone SRL");
    }

    // --- helpers ---

    private void givenNoBankStatement() {
        when(documents.listByCompanyPeriodType(eq(COMPANY), any(LocalDate.class),
                eq(DocumentType.BANK_STATEMENT))).thenReturn(List.of());
    }

    private void givenBankStatement() {
        when(documents.listByCompanyPeriodType(eq(COMPANY), any(LocalDate.class),
                eq(DocumentType.BANK_STATEMENT))).thenReturn(List.of(mock(Document.class)));
    }

    @SafeVarargs
    private void givenTransactions(TxnWithMatches... txns) {
        when(reconciliation.transactionsWithMatches(eq(COMPANY), any(LocalDate.class)))
                .thenReturn(List.of(txns));
    }

    private void givenTransactionList(List<TxnWithMatches> txns) {
        when(reconciliation.transactionsWithMatches(eq(COMPANY), any(LocalDate.class)))
                .thenReturn(txns);
    }

    /** A transaction that requires a document and has no matched invoice. */
    private TxnWithMatches txnMissingDoc(LocalDate date, String partnerName, BigDecimal amount) {
        BankTransaction t = mock(BankTransaction.class);
        when(t.isRequiresDocument()).thenReturn(true);
        when(t.getTxnDate()).thenReturn(date);
        when(t.getPartnerName()).thenReturn(partnerName);
        when(t.getDescription()).thenReturn(null);
        when(t.getAmount()).thenReturn(amount);
        return new TxnWithMatches(t, List.of());
    }

    /** A transaction that requires a document, has no partnerName, falls back to description. */
    private TxnWithMatches txnMissingDocWithoutPartner(LocalDate date, String description, BigDecimal amount) {
        BankTransaction t = mock(BankTransaction.class);
        when(t.isRequiresDocument()).thenReturn(true);
        when(t.getTxnDate()).thenReturn(date);
        when(t.getPartnerName()).thenReturn(null);
        when(t.getDescription()).thenReturn(description);
        when(t.getAmount()).thenReturn(amount);
        return new TxnWithMatches(t, List.of());
    }

    /** A transaction that requires a document but already has a matched invoice — not missing. */
    private TxnWithMatches txnWithInvoice(LocalDate date, String partnerName, BigDecimal amount) {
        BankTransaction t = mock(BankTransaction.class);
        when(t.isRequiresDocument()).thenReturn(true);
        when(t.getTxnDate()).thenReturn(date);
        when(t.getPartnerName()).thenReturn(partnerName);
        when(t.getAmount()).thenReturn(amount);
        MatchedInvoiceView inv = mock(MatchedInvoiceView.class);
        return new TxnWithMatches(t, List.of(inv));
    }

    /** A transaction that does not require any document at all. */
    private TxnWithMatches txnNotRequiringDoc(LocalDate date, String partnerName, BigDecimal amount) {
        BankTransaction t = mock(BankTransaction.class);
        when(t.isRequiresDocument()).thenReturn(false);
        when(t.getTxnDate()).thenReturn(date);
        when(t.getPartnerName()).thenReturn(partnerName);
        when(t.getAmount()).thenReturn(amount);
        return new TxnWithMatches(t, List.of());
    }
}
