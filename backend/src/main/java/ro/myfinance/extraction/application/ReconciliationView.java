package ro.myfinance.extraction.application;

import static ro.myfinance.extraction.application.ReconThresholds.TOLERANCE;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository;
import ro.myfinance.extraction.application.ReconciliationService.CompanyCompleteness;
import ro.myfinance.extraction.application.ReconciliationService.Completeness;
import ro.myfinance.extraction.application.ReconciliationService.DocumentStatus;
import ro.myfinance.extraction.application.ReconciliationService.InvoicePaymentView;
import ro.myfinance.extraction.application.ReconciliationService.InvoicePaymentsView;
import ro.myfinance.extraction.application.ReconciliationService.MatchedInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.OpenInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.OpenTxnView;
import ro.myfinance.extraction.application.ReconciliationService.Payment;
import ro.myfinance.extraction.application.ReconciliationService.TxnWithMatches;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.Invoice;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;

/**
 * Read/reporting views over reconciliation state: period completeness roll-ups, the transaction and
 * invoice payment lists, open-item lists, one-click match suggestions and per-document warning flags.
 * All read-only; the mutating passes live in {@link TransactionMatcher} and {@link RequirementClassifier}.
 */
@Service
public class ReconciliationView {

    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final InvoiceRepository invoices;
    private final TransactionInvoiceMatchRepository matches;

    public ReconciliationView(BankStatementRepository statements, BankTransactionRepository transactions,
                              InvoiceRepository invoices, TransactionInvoiceMatchRepository matches) {
        this.statements = statements;
        this.transactions = transactions;
        this.invoices = invoices;
        this.matches = matches;
    }

    @Transactional(readOnly = true)
    public List<CompanyCompleteness> completenessSummary(java.time.LocalDate periodMonth) {
        java.util.Map<UUID, List<BankStatement>> stmtsByCompany = new java.util.HashMap<>();
        for (BankStatement s : statements.findByPeriodMonth(periodMonth)) {
            stmtsByCompany.computeIfAbsent(s.getCompanyId(), k -> new java.util.ArrayList<>()).add(s);
        }
        java.util.Map<UUID, List<Invoice>> invByCompany = new java.util.HashMap<>();
        for (Invoice i : invoices.findByPeriodMonth(periodMonth.withDayOfMonth(1))) {
            invByCompany.computeIfAbsent(i.getCompanyId(), k -> new java.util.ArrayList<>()).add(i);
        }
        java.util.Set<UUID> companyIds = new java.util.HashSet<>(stmtsByCompany.keySet());
        companyIds.addAll(invByCompany.keySet());

        List<CompanyCompleteness> out = new java.util.ArrayList<>();
        for (UUID companyId : companyIds) {
            List<BankStatement> stmts = stmtsByCompany.get(companyId);
            boolean hasStatements = stmts != null && !stmts.isEmpty();
            int missingTxn = hasStatements ? missingDocTxnCount(stmts) : 0;
            Completeness completeness = !hasStatements ? Completeness.NOT_STARTED
                    : (missingTxn > 0 ? Completeness.PARTIAL : Completeness.COMPLETE);
            InvoiceRollup roll = paymentRollup(invByCompany.get(companyId));
            out.add(new CompanyCompleteness(companyId, completeness, roll.payment(), missingTxn,
                    roll.unmatched()));
        }
        return out;
    }

    /**
     * Count of bank transactions that require a document but aren't fully allocated yet — i.e. the
     * documents the client still owes. Drives both the completeness state and the "N missing" hint.
     */
    private int missingDocTxnCount(List<BankStatement> companyStatements) {
        List<UUID> stmtIds = companyStatements.stream().map(BankStatement::getId).toList();
        List<BankTransaction> reqTxns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds)
                .stream().filter(BankTransaction::isRequiresDocument).toList();
        if (reqTxns.isEmpty()) {
            return 0;
        }
        java.util.Map<UUID, BigDecimal> allocated = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : matches.findByTransactionIdIn(
                reqTxns.stream().map(BankTransaction::getId).toList())) {
            allocated.merge(m.getTransactionId(), m.getAllocatedAmount(), BigDecimal::add);
        }
        return (int) reqTxns.stream().filter(t -> t.getAmount().abs()
                .subtract(allocated.getOrDefault(t.getId(), BigDecimal.ZERO)).compareTo(TOLERANCE) > 0).count();
    }

    /**
     * Payment/matching roll-up over a company's invoices/receipts: COMPLETE when every one is fully
     * paid, PARTIAL when at least one carries an allocation, NONE when nothing is matched (or there are
     * no invoices). The Statements list combines this with statement presence to colour the row dot.
     */
    private record InvoiceRollup(Payment payment, int unmatched) {
    }

    private InvoiceRollup paymentRollup(List<Invoice> companyInvoices) {
        if (companyInvoices == null || companyInvoices.isEmpty()) {
            return new InvoiceRollup(Payment.NONE, 0);
        }
        java.util.Map<UUID, BigDecimal> paidByInvoice = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : matches.findByInvoiceIdIn(
                companyInvoices.stream().map(Invoice::getId).toList())) {
            paidByInvoice.merge(m.getInvoiceId(), m.getAllocatedAmount(), BigDecimal::add);
        }
        int paidInFull = 0;
        int unmatched = 0;
        boolean anyPaid = false;
        for (Invoice i : companyInvoices) {
            BigDecimal paid = paidByInvoice.getOrDefault(i.getId(), BigDecimal.ZERO);
            if (paid.signum() > 0) {
                anyPaid = true;
            } else {
                unmatched++; // uploaded but not linked to any transaction
            }
            if ("PAID".equals(paymentStatus(i.getTotalAmount(), paid))) {
                paidInFull++;
            }
        }
        Payment payment = paidInFull == companyInvoices.size() ? Payment.COMPLETE
                : (anyPaid ? Payment.PARTIAL : Payment.NONE);
        return new InvoiceRollup(payment, unmatched);
    }

    @Transactional(readOnly = true)
    /** Parsed bank statements for a company/period (read view for the statements list). */
    public List<BankStatement> statementsForPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        return statements.findByCompanyIdAndPeriodMonth(companyId, periodMonth.withDayOfMonth(1));
    }

    /** Invoices/receipts filed under a company/period (manual-link candidate list). */
    public List<Invoice> invoicesForPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        return invoices.findByCompanyIdAndPeriodMonth(companyId, periodMonth.withDayOfMonth(1));
    }

    public List<TxnWithMatches> transactionsWithMatches(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        List<UUID> stmtIds = statements.findByCompanyIdAndPeriodMonth(companyId, period)
                .stream().map(BankStatement::getId).toList();
        if (stmtIds.isEmpty()) {
            return List.of();
        }
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds);
        List<UUID> txnIds = txns.stream().map(BankTransaction::getId).toList();
        List<TransactionInvoiceMatch> links = matches.findByTransactionIdIn(txnIds);
        java.util.Map<UUID, Invoice> invById = new java.util.HashMap<>();
        for (Invoice i : invoices.findAllById(links.stream().map(TransactionInvoiceMatch::getInvoiceId).toList())) {
            invById.put(i.getId(), i);
        }
        java.util.Map<UUID, List<MatchedInvoiceView>> byTxn = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : links) {
            Invoice i = invById.get(m.getInvoiceId());
            if (i != null) {
                byTxn.computeIfAbsent(m.getTransactionId(), k -> new java.util.ArrayList<>())
                        .add(matchedView(i, m));
            }
        }
        return txns.stream()
                .map(t -> new TxnWithMatches(t, byTxn.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /**
     * Invoices still open for payment (remaining &gt; 0) for a company, within a rolling window ending
     * at {@code periodMonth} and reaching {@code months} back. Lets the link picker reach invoices
     * uploaded in earlier months that an installment in the current month settles. Invoices with an
     * unknown total (image-only receipts) are considered open until they carry any allocation.
     */
    @Transactional(readOnly = true)
    public List<OpenInvoiceView> openInvoices(UUID companyId, java.time.LocalDate periodMonth, int months) {
        return openInvoices(companyId, periodMonth, months, false);
    }

    /**
     * As {@link #openInvoices(UUID, java.time.LocalDate, int)}, but when {@code includeMapped} is true
     * the fully-allocated invoices are also returned (with {@code remaining} 0) — the reconciliation
     * workspace shows them dimmed under its "All" filter, not just the still-open ones.
     */
    @Transactional(readOnly = true)
    public List<OpenInvoiceView> openInvoices(UUID companyId, java.time.LocalDate periodMonth, int months,
                                              boolean includeMapped) {
        java.time.LocalDate to = periodMonth.withDayOfMonth(1);
        java.time.LocalDate from = to.minusMonths(months);
        List<Invoice> invs = invoices.findByCompanyIdAndPeriodMonthBetween(companyId, from, to);
        if (invs.isEmpty()) {
            return List.of();
        }
        java.util.Map<UUID, BigDecimal> paid = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : matches.findByInvoiceIdIn(invs.stream().map(Invoice::getId).toList())) {
            paid.merge(m.getInvoiceId(), m.getAllocatedAmount(), BigDecimal::add);
        }
        // Duplicate flag over the same window (supplier + amount, dates within tolerance).
        java.util.Map<String, List<Invoice>> byKey = new java.util.HashMap<>();
        for (Invoice w : invs) {
            String key = invoiceDupKey(w);
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(w);
            }
        }
        List<OpenInvoiceView> out = new java.util.ArrayList<>();
        for (Invoice i : invs) {
            BigDecimal p = paid.getOrDefault(i.getId(), BigDecimal.ZERO);
            BigDecimal remaining = i.getTotalAmount() == null ? null : i.getTotalAmount().subtract(p);
            boolean open = i.getTotalAmount() != null ? remaining.compareTo(TOLERANCE) > 0 : p.signum() == 0;
            if (open || includeMapped) {
                String key = invoiceDupKey(i);
                boolean duplicate = key != null && byKey.getOrDefault(key, List.of()).stream()
                        .anyMatch(o -> !o.getId().equals(i.getId()) && sameInvoice(i, o));
                out.add(new OpenInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                        i.getSupplierName(), i.getSupplierIban(), i.getTotalAmount(), i.getInvoiceDate(),
                        i.getPeriodMonth(), p, remaining, duplicate, i.getWrongParty(),
                        i.getIssuerCif(), i.getClientCif()));
            }
        }
        return out;
    }

    /** Invoice-centric payments view, looked up by the invoice's document id. */
    @Transactional(readOnly = true)
    public InvoicePaymentsView invoicePaymentsByDocument(UUID companyId, UUID documentId) {
        Invoice inv = invoices.findByDocumentId(documentId)
                .filter(i -> i.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Invoice not found for document " + documentId));
        List<TransactionInvoiceMatch> links = matches.findByInvoiceIdIn(List.of(inv.getId()));
        java.util.Map<UUID, BankTransaction> txById = new java.util.HashMap<>();
        for (BankTransaction t : transactions.findAllById(
                links.stream().map(TransactionInvoiceMatch::getTransactionId).toList())) {
            txById.put(t.getId(), t);
        }
        BigDecimal paid = BigDecimal.ZERO;
        List<InvoicePaymentView> payments = new java.util.ArrayList<>();
        for (TransactionInvoiceMatch m : links) {
            paid = paid.add(m.getAllocatedAmount());
            BankTransaction t = txById.get(m.getTransactionId());
            if (t != null) {
                payments.add(new InvoicePaymentView(t.getId(), t.getTxnDate(), t.getPartnerName(),
                        t.getAmount(), m.getAllocatedAmount()));
            }
        }
        BigDecimal remaining = inv.getTotalAmount() == null ? null : inv.getTotalAmount().subtract(paid);
        return new InvoicePaymentsView(inv.getId(), inv.getDocumentId(), inv.getOriginalFilename(),
                inv.getSupplierName(), inv.getTotalAmount(), inv.getInvoiceDate(), paid, remaining,
                paymentStatus(inv.getTotalAmount(), paid), payments);
    }

    private String paymentStatus(BigDecimal total, BigDecimal paid) {
        if (paid.signum() == 0) {
            return "UNPAID";
        }
        if (total != null && total.subtract(paid).compareTo(TOLERANCE) <= 0) {
            return "PAID";
        }
        return "PARTIAL";
    }

    /**
     * Transactions still open for allocation (need a document, remaining &gt; 0) for a company within a
     * rolling window ending at {@code periodMonth}. Used to add a payment from the invoice-centric view.
     */
    @Transactional(readOnly = true)
    public List<OpenTxnView> openTransactions(UUID companyId, java.time.LocalDate periodMonth, int months) {
        java.time.LocalDate to = periodMonth.withDayOfMonth(1);
        java.time.LocalDate from = to.minusMonths(months);
        List<UUID> stmtIds = statements.findByCompanyIdAndPeriodMonthBetween(companyId, from, to)
                .stream().map(BankStatement::getId).toList();
        if (stmtIds.isEmpty()) {
            return List.of();
        }
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds);
        java.util.Map<UUID, BigDecimal> allocated = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : matches.findByTransactionIdIn(txns.stream().map(BankTransaction::getId).toList())) {
            allocated.merge(m.getTransactionId(), m.getAllocatedAmount(), BigDecimal::add);
        }
        List<OpenTxnView> out = new java.util.ArrayList<>();
        for (BankTransaction t : txns) {
            if (!t.isRequiresDocument()) {
                continue;
            }
            BigDecimal alloc = allocated.getOrDefault(t.getId(), BigDecimal.ZERO);
            BigDecimal remaining = t.getAmount().abs().subtract(alloc);
            if (remaining.compareTo(TOLERANCE) > 0) {
                out.add(new OpenTxnView(t.getId(), t.getTxnDate(), t.getAmount(), t.getPartnerName(),
                        t.getPartnerIban(), alloc, remaining));
            }
        }
        return out;
    }

    private MatchedInvoiceView matchedView(Invoice i, TransactionInvoiceMatch m) {
        BigDecimal remaining = i.getTotalAmount() == null ? null
                : i.getTotalAmount().subtract(matches.sumAllocatedByInvoice(i.getId()));
        return new MatchedInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                i.getTotalAmount(), i.getInvoiceDate(), i.getSupplierName(), m.getAllocatedAmount(), remaining);
    }

    /** The matched-invoice views for a single transaction (used by setRequirement's response). */
    public List<MatchedInvoiceView> matchedViewsFor(UUID txnId) {
        List<TransactionInvoiceMatch> links = matches.findByTransactionIdIn(List.of(txnId));
        if (links.isEmpty()) {
            return List.of();
        }
        java.util.Map<UUID, Invoice> invById = new java.util.HashMap<>();
        for (Invoice i : invoices.findAllById(links.stream().map(TransactionInvoiceMatch::getInvoiceId).toList())) {
            invById.put(i.getId(), i);
        }
        List<MatchedInvoiceView> out = new java.util.ArrayList<>();
        for (TransactionInvoiceMatch m : links) {
            Invoice i = invById.get(m.getInvoiceId());
            if (i != null) {
                out.add(matchedView(i, m));
            }
        }
        return out;
    }

    /**
     * Warning flags per document for the company+period: bank statements with no transaction in the
     * uploaded period; invoices that match no transaction or whose date falls outside the period.
     */
    @Transactional(readOnly = true)
    public List<DocumentStatus> documentStatuses(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        List<DocumentStatus> out = new java.util.ArrayList<>();

        for (BankStatement s : statements.findByCompanyIdAndPeriodMonth(companyId, period)) {
            List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(List.of(s.getId()));
            long total = txns.size();
            long in = txns.stream().filter(t -> t.getTxnDate().withDayOfMonth(1).equals(period)).count();
            String dateFlag;
            String reason;
            if (total == 0 || in == 0) {
                dateFlag = "RED";
                reason = "no_transactions_in_period";
            } else if (in < total) {
                dateFlag = "ORANGE";
                reason = "some_transactions_outside_period";
            } else {
                dateFlag = null;
                reason = null;
            }
            out.add(new DocumentStatus(s.getDocumentId(), dateFlag, reason, false, null, null, null, null,
                    null, null, null));
        }

        List<Invoice> invs = invoices.findByCompanyIdAndPeriodMonth(companyId, period);
        if (!invs.isEmpty()) {
            // Allocation-aware: paid per invoice across ALL its matches (payments can span months).
            java.util.Map<UUID, BigDecimal> paid = new java.util.HashMap<>();
            for (TransactionInvoiceMatch m : matches.findByInvoiceIdIn(invs.stream().map(Invoice::getId).toList())) {
                paid.merge(m.getInvoiceId(), m.getAllocatedAmount(), BigDecimal::add);
            }
            // Duplicate detection over current + last 3 periods, keyed by supplier + amount (filename
            // ignored — the same invoice can be re-uploaded under a different name). Issue dates are
            // compared with a few days' tolerance: extraction can read a slightly different date from
            // the same invoice, yet recurring monthly charges (~30 days apart) stay distinct.
            java.util.Map<String, List<Invoice>> byKey = new java.util.HashMap<>();
            for (Invoice w : invoices.findByCompanyIdAndPeriodMonthBetween(companyId, period.minusMonths(3), period)) {
                String key = invoiceDupKey(w);
                if (key != null) {
                    byKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(w);
                }
            }
            for (Invoice inv : invs) {
                BigDecimal p = paid.getOrDefault(inv.getId(), BigDecimal.ZERO);
                boolean dateOutside = inv.getInvoiceDate() != null
                        && !inv.getInvoiceDate().withDayOfMonth(1).equals(period);
                String key = invoiceDupKey(inv);
                boolean duplicate = key != null && byKey.getOrDefault(key, List.of()).stream()
                        .anyMatch(o -> !o.getId().equals(inv.getId()) && sameInvoice(inv, o));
                out.add(new DocumentStatus(inv.getDocumentId(),
                        dateOutside ? "RED" : null, dateOutside ? "date_outside_period" : null, duplicate,
                        paymentStatus(inv.getTotalAmount(), p),
                        inv.getWrongParty(), inv.getClientCif(), inv.getSupplierName(),
                        inv.getIssuerCif(), inv.getTotalAmount(), inv.getInvoiceDate()));
            }
        }
        return out;
    }

    /** How many days two issue dates may differ and still be treated as the same invoice (extraction noise). */
    private static final long DUP_DATE_TOLERANCE_DAYS = 6;

    /** Group key for duplicate detection: supplier (IBAN, else normalized name) + amount. Null if undeterminable. */
    private String invoiceDupKey(Invoice i) {
        if (i.getTotalAmount() == null) {
            return null;
        }
        String supplier = i.getSupplierIban() != null ? "I:" + i.getSupplierIban()
                : (i.getSupplierName() != null ? "N:" + ReconText.normalize(i.getSupplierName()) : null);
        if (supplier == null) {
            return null;
        }
        return supplier + "|" + i.getTotalAmount().stripTrailingZeros().toPlainString();
    }

    /**
     * Whether two invoices that share a supplier+amount key are actually the same document (a
     * duplicate). When both carry an extracted invoice number, that is authoritative — distinct numbers
     * are distinct invoices, even at the same amount a few days apart (e.g. two SAGA subscriptions). Only
     * when a number is missing do we fall back to issue-date proximity (extraction noise tolerance).
     */
    private boolean sameInvoice(Invoice a, Invoice b) {
        String na = normNumber(a.getReceiptNumber());
        String nb = normNumber(b.getReceiptNumber());
        if (na != null && nb != null) {
            return na.equals(nb);
        }
        return datesClose(a.getInvoiceDate(), b.getInvoiceDate());
    }

    /** Invoice/series number reduced to a comparison key (alphanumerics, upper-cased); null if blank. */
    private static String normNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return t.isEmpty() ? null : t;
    }

    /** Same-invoice date check: within tolerance, or flagged when either date is missing (can't distinguish). */
    private boolean datesClose(java.time.LocalDate a, java.time.LocalDate b) {
        if (a == null || b == null) {
            return true;
        }
        return Math.abs(java.time.temporal.ChronoUnit.DAYS.between(a, b)) <= DUP_DATE_TOLERANCE_DAYS;
    }
}
