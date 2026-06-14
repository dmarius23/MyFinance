package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionRuleRepository;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.Invoice;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;
import ro.myfinance.extraction.domain.TransactionRule;

/** Document-requirement classification, accountant overrides (with learned rules), completeness. */
@Service
@Transactional
public class ReconciliationService {

    public enum Completeness { NOT_STARTED, PARTIAL, COMPLETE }

    public record CompanyCompleteness(UUID companyId, Completeness completeness) {
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
                                  java.math.BigDecimal paidAmount, java.math.BigDecimal remaining) {
    }

    /** Per-document warning flags for the documents list. warningReason is an i18n key code. */
    public record DocumentStatus(UUID documentId, boolean warning, String warningReason, boolean unmatched) {
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

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    /**
     * How many days a payment may precede the extracted invoice date and still auto-match. The
     * extracted date can land on a due date while the payment was made a few days earlier; with an
     * exact IBAN + amount match already in hand, a small backward window avoids rejecting it.
     */
    private static final long DATE_BACK_TOLERANCE_DAYS = 10;

    private final TransactionClassifier classifier;
    private final TransactionRuleRepository rules;
    private final BankTransactionRepository transactions;
    private final BankStatementRepository statements;
    private final CompanyRepository companies;
    private final InvoiceRepository invoices;
    private final TransactionInvoiceMatchRepository matches;
    private final AuditRecorder audit;

    public ReconciliationService(TransactionClassifier classifier, TransactionRuleRepository rules,
                                 BankTransactionRepository transactions, BankStatementRepository statements,
                                 CompanyRepository companies, InvoiceRepository invoices,
                                 TransactionInvoiceMatchRepository matches, AuditRecorder audit) {
        this.classifier = classifier;
        this.rules = rules;
        this.transactions = transactions;
        this.statements = statements;
        this.companies = companies;
        this.invoices = invoices;
        this.matches = matches;
        this.audit = audit;
    }

    /** Classify a freshly-parsed statement's transactions (skips any already set by an accountant). */
    public void classify(UUID statementId) {
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(List.of(statementId));
        if (txns.isEmpty()) {
            return;
        }
        UUID companyId = txns.get(0).getCompanyId();
        String companyName = companies.findById(companyId).map(c -> c.getLegalName()).orElse(null);
        List<TransactionRule> learned = rules.findByCompanyId(companyId);

        for (BankTransaction t : txns) {
            if (t.getDecisionSource() == DecisionSource.ACCOUNTANT_SET) {
                continue;
            }
            var base = classifier.classify(new TransactionClassifier.Input(
                    t.getAmount().signum() > 0, t.getPartnerIban(), t.getPartnerName(),
                    t.getDescription(), t.getAccountIban(), companyName));
            t.setCategory(base.category());
            TransactionRule rule = matchRule(learned, t);
            if (rule != null) {
                t.setRequiresDocument(rule.isRequiresDocument());
                t.setDecisionSource(DecisionSource.LEARNED_RULE);
            } else {
                t.setRequiresDocument(base.requiresDocument());
                t.setDecisionSource(DecisionSource.SYSTEM_RULE);
            }
        }
    }

    /** Accountant override: set the requirement, remember it as a learned rule. Returns the txn with its current matches. */
    public TxnWithMatches setRequirement(UUID txnId, boolean requiresDocument, String reason) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        t.setRequiresDocument(requiresDocument);
        t.setDecisionSource(DecisionSource.ACCOUNTANT_SET);
        t.setOverrideReason(reason);

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        String descNorm = ReconText.normalize(t.getDescription());
        TransactionRule existing = rules.findByCompanyId(t.getCompanyId()).stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setRequiresDocument(requiresDocument);
        } else {
            rules.save(new TransactionRule(tenantId, t.getCompanyId(), t.getPartnerIban(),
                    descNorm, requiresDocument, userId));
        }
        audit.record("TXN_REQUIREMENT_SET", "bank_transaction", txnId);
        return new TxnWithMatches(t, matchedViewsFor(txnId));
    }

    @Transactional(readOnly = true)
    public List<CompanyCompleteness> completenessSummary(java.time.LocalDate periodMonth) {
        java.util.Map<UUID, List<BankStatement>> byCompany = new java.util.HashMap<>();
        for (BankStatement s : statements.findByPeriodMonth(periodMonth)) {
            byCompany.computeIfAbsent(s.getCompanyId(), k -> new java.util.ArrayList<>()).add(s);
        }
        List<CompanyCompleteness> out = new java.util.ArrayList<>();
        for (var e : byCompany.entrySet()) {
            List<UUID> stmtIds = e.getValue().stream().map(BankStatement::getId).toList();
            List<BankTransaction> reqTxns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds)
                    .stream().filter(BankTransaction::isRequiresDocument).toList();
            boolean missing;
            if (reqTxns.isEmpty()) {
                missing = false;
            } else {
                // A transaction that needs a document is satisfied only when fully allocated.
                java.util.Map<UUID, BigDecimal> allocated = new java.util.HashMap<>();
                for (TransactionInvoiceMatch m : matches.findByTransactionIdIn(
                        reqTxns.stream().map(BankTransaction::getId).toList())) {
                    allocated.merge(m.getTransactionId(), m.getAllocatedAmount(), BigDecimal::add);
                }
                missing = reqTxns.stream().anyMatch(t -> t.getAmount().abs()
                        .subtract(allocated.getOrDefault(t.getId(), BigDecimal.ZERO)).compareTo(TOLERANCE) > 0);
            }
            out.add(new CompanyCompleteness(e.getKey(), missing ? Completeness.PARTIAL : Completeness.COMPLETE));
        }
        return out;
    }

    public void matchPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        List<UUID> stmtIds = statements.findByCompanyIdAndPeriodMonth(companyId, period)
                .stream().map(BankStatement::getId).toList();
        if (stmtIds.isEmpty()) {
            return;
        }
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds);
        List<UUID> txnIds = txns.stream().map(BankTransaction::getId).toList();
        java.util.Set<UUID> matchedTxnIds = new java.util.HashSet<>();
        java.util.Set<UUID> usedInvoiceIds = new java.util.HashSet<>();
        for (TransactionInvoiceMatch m : matches.findByTransactionIdIn(txnIds)) {
            matchedTxnIds.add(m.getTransactionId());
            usedInvoiceIds.add(m.getInvoiceId());
        }
        List<Invoice> periodInvoices = invoices.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .filter(i -> i.getSupplierIban() != null && i.getTotalAmount() != null
                        && !usedInvoiceIds.contains(i.getId()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        for (BankTransaction t : txns) {
            if (!t.isRequiresDocument() || matchedTxnIds.contains(t.getId()) || t.getPartnerIban() == null) {
                continue;
            }
            Invoice hit = null;
            for (Invoice inv : periodInvoices) {
                boolean ibanOk = inv.getSupplierIban().equals(t.getPartnerIban());
                boolean amtOk = inv.getTotalAmount().abs().subtract(t.getAmount().abs()).abs()
                        .compareTo(TOLERANCE) <= 0;
                boolean dateOk = inv.getInvoiceDate() == null
                        || !t.getTxnDate().isBefore(inv.getInvoiceDate().minusDays(DATE_BACK_TOLERANCE_DAYS));
                if (ibanOk && amtOk && dateOk) {
                    hit = inv;
                    break;
                }
            }
            if (hit != null) {
                // Exact 1:1 match: the payment fully settles the invoice, so allocate its full total.
                matches.save(new TransactionInvoiceMatch(tenantId, t.getId(), hit.getId(), "AUTO", null,
                        hit.getTotalAmount()));
                matchedTxnIds.add(t.getId());
                periodInvoices.remove(hit);
            }
        }
    }

    /**
     * Link a transaction to an invoice, allocating {@code requestedAmount} of the payment to it.
     * When {@code requestedAmount} is null the allocation defaults to the smaller of the transaction's
     * and the invoice's remaining amounts (so the common full-payment case needs no number). Allocation
     * is capped at both remainings, so an invoice can't be over-paid nor a payment over-allocated.
     */
    public void link(UUID companyId, UUID txnId, UUID invoiceId, BigDecimal requestedAmount) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        if (!t.getCompanyId().equals(companyId) || !inv.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Not found in company " + companyId);
        }
        if (matches.existsByTransactionIdAndInvoiceId(txnId, invoiceId)) {
            return; // already linked; editing the allocation comes with the split UI (later slice)
        }
        if (inv.getInvoiceDate() != null
                && t.getTxnDate().isBefore(inv.getInvoiceDate().minusDays(DATE_BACK_TOLERANCE_DAYS))) {
            throw new IllegalArgumentException("Transaction date is before the invoice date");
        }

        BigDecimal txnRemaining = t.getAmount().abs().subtract(matches.sumAllocatedByTransaction(txnId));
        BigDecimal invRemaining = inv.getTotalAmount() == null ? null
                : inv.getTotalAmount().subtract(matches.sumAllocatedByInvoice(invoiceId));
        if (txnRemaining.compareTo(TOLERANCE) <= 0) {
            throw new IllegalArgumentException("Transaction is already fully allocated");
        }
        if (invRemaining != null && invRemaining.compareTo(TOLERANCE) <= 0) {
            throw new IllegalArgumentException("Invoice is already fully paid");
        }

        BigDecimal defaultAmount = invRemaining == null ? txnRemaining : txnRemaining.min(invRemaining);
        BigDecimal amount = requestedAmount != null ? requestedAmount : defaultAmount;
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Allocation must be a positive amount");
        }
        if (amount.subtract(txnRemaining).compareTo(TOLERANCE) > 0) {
            throw new IllegalArgumentException("Allocation exceeds the transaction's remaining amount");
        }
        if (invRemaining != null && amount.subtract(invRemaining).compareTo(TOLERANCE) > 0) {
            throw new IllegalArgumentException("Allocation exceeds the invoice's remaining amount");
        }

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        matches.save(new TransactionInvoiceMatch(tenantId, txnId, invoiceId, "MANUAL", userId, amount));
        audit.record("TXN_INVOICE_LINKED", "bank_transaction", txnId);
    }

    public void unlink(UUID companyId, UUID txnId, UUID invoiceId) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        if (!t.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Not found in company " + companyId);
        }
        if (matches.existsByTransactionIdAndInvoiceId(txnId, invoiceId)) {
            matches.deleteByTransactionIdAndInvoiceId(txnId, invoiceId);
            audit.record("TXN_INVOICE_UNLINKED", "bank_transaction", txnId);
        }
    }

    @Transactional(readOnly = true)
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
        List<OpenInvoiceView> out = new java.util.ArrayList<>();
        for (Invoice i : invs) {
            BigDecimal p = paid.getOrDefault(i.getId(), BigDecimal.ZERO);
            BigDecimal remaining = i.getTotalAmount() == null ? null : i.getTotalAmount().subtract(p);
            boolean open = i.getTotalAmount() != null ? remaining.compareTo(TOLERANCE) > 0 : p.signum() == 0;
            if (open) {
                out.add(new OpenInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                        i.getSupplierName(), i.getSupplierIban(), i.getTotalAmount(), i.getInvoiceDate(),
                        i.getPeriodMonth(), p, remaining));
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
    private List<MatchedInvoiceView> matchedViewsFor(UUID txnId) {
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
            boolean inPeriod = transactions.findByStatementIdInOrderByTxnDateDesc(List.of(s.getId())).stream()
                    .anyMatch(t -> t.getTxnDate().withDayOfMonth(1).equals(period));
            out.add(new DocumentStatus(s.getDocumentId(), !inPeriod,
                    inPeriod ? null : "no_transactions_in_period", false));
        }

        List<Invoice> invs = invoices.findByCompanyIdAndPeriodMonth(companyId, period);
        if (!invs.isEmpty()) {
            // Allocation-aware: paid per invoice across ALL its matches (payments can span months).
            java.util.Map<UUID, BigDecimal> paid = new java.util.HashMap<>();
            for (TransactionInvoiceMatch m : matches.findByInvoiceIdIn(invs.stream().map(Invoice::getId).toList())) {
                paid.merge(m.getInvoiceId(), m.getAllocatedAmount(), BigDecimal::add);
            }
            for (Invoice inv : invs) {
                BigDecimal p = paid.getOrDefault(inv.getId(), BigDecimal.ZERO);
                boolean fullyPaid = inv.getTotalAmount() != null
                        ? inv.getTotalAmount().subtract(p).compareTo(TOLERANCE) <= 0
                        : p.signum() > 0; // unknown total (image receipt): any allocation counts as matched
                boolean dateInPeriod = inv.getInvoiceDate() != null
                        && inv.getInvoiceDate().withDayOfMonth(1).equals(period);
                out.add(new DocumentStatus(inv.getDocumentId(), !dateInPeriod,
                        dateInPeriod ? null : "date_outside_period", !fullyPaid));
            }
        }
        return out;
    }

    private TransactionRule matchRule(List<TransactionRule> learned, BankTransaction t) {
        String descNorm = ReconText.normalize(t.getDescription());
        return learned.stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
    }
}
