package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.Invoice;

/**
 * Reconciliation facade. Owns the public API and view records used by controllers, {@code PortalService},
 * listeners and {@code DocumentReminderService}, and delegates to three focused collaborators:
 * {@link RequirementClassifier} (document-requirement rules + accountant overrides),
 * {@link TransactionMatcher} (3-tier auto-match + manual link/unlink allocations) and
 * {@link ReconciliationView} (completeness, read views, open lists, suggestions, per-document flags).
 */
@Service
@Transactional
public class ReconciliationService {

    public enum Completeness { NOT_STARTED, PARTIAL, COMPLETE }

    /** Payment/matching roll-up over a company's invoices/receipts for a period. */
    public enum Payment { NONE, PARTIAL, COMPLETE }

    public record CompanyCompleteness(UUID companyId, Completeness completeness, Payment payment,
                                      int missingTxnCount, int unmatchedInvoiceCount) {
    }

    public record MatchedInvoiceView(UUID invoiceId, UUID documentId, String filename,
                                     java.math.BigDecimal totalAmount, java.time.LocalDate invoiceDate,
                                     String supplierName, java.math.BigDecimal allocatedAmount,
                                     java.math.BigDecimal invoiceRemaining) {
    }

    public record TxnWithMatches(BankTransaction txn, java.util.List<MatchedInvoiceView> invoices) {
    }

    /** An invoice still open for payment (remaining > 0), within the cross-period link window. */
    public record OpenInvoiceView(UUID invoiceId, UUID documentId, String filename, String supplierName,
                                  String supplierIban, java.math.BigDecimal totalAmount,
                                  java.time.LocalDate invoiceDate, java.time.LocalDate periodMonth,
                                  java.math.BigDecimal paidAmount, java.math.BigDecimal remaining,
                                  boolean duplicate, Boolean wrongParty, String issuerCif, String clientCif) {
    }

    /**
     * Per-document flags for the documents list.
     * - dateFlag: "RED" (all outside the period / invoice date outside), "ORANGE" (statement has some
     *   transactions outside the period), or null; dateReason is an i18n key for the tooltip.
     * - paymentStatus: UNPAID/PARTIAL/PAID for invoices/receipts, null for statements.
     * - wrongParty: TRUE (different fiscal code), FALSE (matches), null (undetermined / unidentified).
     * - clientCif: the buyer fiscal code read off the document (for display).
     * - issuer: the supplier/issuer name read off the invoice or receipt (null for statements).
     */
    public record DocumentStatus(UUID documentId, String dateFlag, String dateReason, boolean duplicate,
                                 String paymentStatus, Boolean wrongParty, String clientCif, String issuer,
                                 String issuerCif, BigDecimal total, java.time.LocalDate invoiceDate) {
    }

    /** A single payment (transaction allocation) applied to an invoice. */
    public record InvoicePaymentView(UUID txnId, java.time.LocalDate txnDate, String partnerName,
                                     java.math.BigDecimal amount, java.math.BigDecimal allocatedAmount) {
    }

    /** Invoice-centric view: the invoice plus the payments applied to it and its remaining balance. */
    public record InvoicePaymentsView(UUID invoiceId, UUID documentId, String filename, String supplierName,
                                      java.math.BigDecimal totalAmount, java.time.LocalDate invoiceDate,
                                      java.math.BigDecimal paidAmount, java.math.BigDecimal remaining,
                                      String status, java.util.List<InvoicePaymentView> payments) {
    }

    /** A transaction still open for allocation (needs a document, remaining &gt; 0). */
    public record OpenTxnView(UUID id, java.time.LocalDate txnDate, java.math.BigDecimal amount,
                              String partnerName, String partnerIban, java.math.BigDecimal allocatedAmount,
                              java.math.BigDecimal remaining) {
    }

    /**
     * A bank-statement FILE relevant to a month, for the reconcile files panel. {@code status} is
     * EXTRACTED / NEEDS_REVIEW / DUPLICATE / NO_TXN; {@code coveredFrom}/{@code coveredTo} is the file's real
     * range (null for legacy/unparsed); {@code txnsInMonth} counts its transactions dated in the shown month;
     * {@code matchedInMonth} how many of those are reconciled to invoices (delete impact). {@code mine} is
     * true when the current user uploaded it, and {@code deletable} adds "not a Drive-synced file".
     */
    public record StatementFileView(UUID documentId, String filename, String source,
                                    java.time.Instant uploadedAt, boolean mine, boolean deletable,
                                    String status, java.time.LocalDate coveredFrom, java.time.LocalDate coveredTo,
                                    int txnsInMonth, int matchedInMonth) {
    }

    /** One proposed transaction↔invoice allocation within a suggestion. */
    public record SuggestionLink(UUID transactionId, java.time.LocalDate txnDate, String partnerName,
                                 java.math.BigDecimal txnAmount, UUID invoiceId, String invoiceFilename,
                                 String supplierName, java.math.BigDecimal amount) {
    }

    /** A one-click match proposal: EXACT (cross-period 1:1), SPLIT (1 txn → N invoices), INSTALLMENT (N txns → 1 invoice). */
    public record MatchSuggestion(String kind, java.util.List<SuggestionLink> links) {
    }

    private final RequirementClassifier classifier;
    private final TransactionMatcher matcher;
    private final ReconciliationView view;
    private final MatchSuggester suggester;

    public ReconciliationService(RequirementClassifier classifier, TransactionMatcher matcher,
                                 ReconciliationView view, MatchSuggester suggester) {
        this.classifier = classifier;
        this.matcher = matcher;
        this.view = view;
        this.suggester = suggester;
    }

    /** Classify a freshly-parsed statement's transactions (skips any already set by an accountant). */
    public void classify(UUID statementId) {
        classifier.classify(statementId);
    }

    /** Accountant override: set the requirement, remember it as a learned rule. Returns the txn with its current matches. */
    public TxnWithMatches setRequirement(UUID txnId, boolean requiresDocument, String reason) {
        BankTransaction t = classifier.applyOverride(txnId, requiresDocument, reason);
        return new TxnWithMatches(t, view.matchedViewsFor(t.getId()));
    }

    @Transactional(readOnly = true)
    public List<CompanyCompleteness> completenessSummary(java.time.LocalDate periodMonth) {
        return view.completenessSummary(periodMonth);
    }

    public void matchPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        matcher.matchPeriod(companyId, periodMonth);
    }

    public void link(UUID companyId, UUID txnId, UUID invoiceId, BigDecimal requestedAmount) {
        matcher.link(companyId, txnId, invoiceId, requestedAmount);
    }

    public void unlink(UUID companyId, UUID txnId, UUID invoiceId) {
        matcher.unlink(companyId, txnId, invoiceId);
    }

    /** Parsed bank statements for a company/period (read view for the statements list). */
    @Transactional(readOnly = true)
    public List<BankStatement> statementsForPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        return view.statementsForPeriod(companyId, periodMonth);
    }

    /** Invoices/receipts filed under a company/period (manual-link candidate list). */
    public List<Invoice> invoicesForPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        return view.invoicesForPeriod(companyId, periodMonth);
    }

    /** Bank-statement files relevant to a month (files panel): status, range, this-month + matched counts,
     *  and delete-own ownership. */
    @Transactional(readOnly = true)
    public List<StatementFileView> statementFiles(UUID companyId, java.time.LocalDate periodMonth) {
        return view.statementFiles(companyId, periodMonth);
    }

    public List<TxnWithMatches> transactionsWithMatches(UUID companyId, java.time.LocalDate periodMonth) {
        return view.transactionsWithMatches(companyId, periodMonth);
    }

    /**
     * Invoices still open for payment (remaining &gt; 0) for a company, within a rolling window ending
     * at {@code periodMonth} and reaching {@code months} back.
     */
    @Transactional(readOnly = true)
    public List<OpenInvoiceView> openInvoices(UUID companyId, java.time.LocalDate periodMonth, int months) {
        return view.openInvoices(companyId, periodMonth, months);
    }

    /**
     * As {@link #openInvoices(UUID, java.time.LocalDate, int)}, but when {@code includeMapped} is true
     * the fully-allocated invoices are also returned (with {@code remaining} 0).
     */
    @Transactional(readOnly = true)
    public List<OpenInvoiceView> openInvoices(UUID companyId, java.time.LocalDate periodMonth, int months,
                                              boolean includeMapped) {
        return view.openInvoices(companyId, periodMonth, months, includeMapped);
    }

    /** Invoice-centric payments view, looked up by the invoice's document id. */
    @Transactional(readOnly = true)
    public InvoicePaymentsView invoicePaymentsByDocument(UUID companyId, UUID documentId) {
        return view.invoicePaymentsByDocument(companyId, documentId);
    }

    /**
     * Transactions still open for allocation (need a document, remaining &gt; 0) for a company within a
     * rolling window ending at {@code periodMonth}.
     */
    @Transactional(readOnly = true)
    public List<OpenTxnView> openTransactions(UUID companyId, java.time.LocalDate periodMonth, int months) {
        return view.openTransactions(companyId, periodMonth, months);
    }

    /**
     * Non-trivial match proposals for the period the accountant is reconciling: cross-period exacts,
     * SPLIT (one payment → several invoices) and INSTALLMENT (several payments → one invoice).
     */
    @Transactional(readOnly = true)
    public List<MatchSuggestion> suggestions(UUID companyId, java.time.LocalDate periodMonth) {
        return suggester.suggestions(companyId, periodMonth);
    }

    /**
     * Warning flags per document for the company+period: bank statements with no transaction in the
     * uploaded period; invoices that match no transaction or whose date falls outside the period.
     */
    @Transactional(readOnly = true)
    public List<DocumentStatus> documentStatuses(UUID companyId, java.time.LocalDate periodMonth) {
        return view.documentStatuses(companyId, periodMonth);
    }
}
