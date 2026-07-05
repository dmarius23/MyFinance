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

    /** One proposed transaction↔invoice allocation within a suggestion. */
    public record SuggestionLink(UUID transactionId, java.time.LocalDate txnDate, String partnerName,
                                 java.math.BigDecimal txnAmount, UUID invoiceId, String invoiceFilename,
                                 String supplierName, java.math.BigDecimal amount) {
    }

    /** A one-click match proposal: EXACT (cross-period 1:1), SPLIT (1 txn → N invoices), INSTALLMENT (N txns → 1 invoice). */
    public record MatchSuggestion(String kind, java.util.List<SuggestionLink> links) {
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
                .filter(i -> i.getTotalAmount() != null
                        && !usedInvoiceIds.contains(i.getId())
                        // A wrong-party invoice/receipt (client CIF ≠ this company) is not ours to settle,
                        // so it must never be auto-matched against our bank transactions.
                        && !Boolean.TRUE.equals(i.getWrongParty()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        // Tier 1 — exact supplier IBAN + amount + date. Highest confidence: settle these first so a
        // payment that carries the supplier's account is never claimed by a weaker name match.
        for (BankTransaction t : txns) {
            if (!t.isRequiresDocument() || matchedTxnIds.contains(t.getId()) || t.getPartnerIban() == null) {
                continue;
            }
            Invoice hit = firstInvoice(periodInvoices, inv ->
                    inv.getSupplierIban() != null && inv.getSupplierIban().equals(t.getPartnerIban())
                            && amountMatches(inv, t) && dateOk(inv, t));
            if (hit != null) {
                autoMatch(tenantId, t, hit, matchedTxnIds, periodInvoices);
            }
        }
        // Tier 2 — exact amount + supplier-name match, for payments whose IBAN doesn't align (POS/card,
        // or the invoice's collection account differs from the paying account). Restricted to debits (a
        // purchase is paid out) so an incoming credit of the same amount can't be claimed. The exact
        // amount plus a distinctive name token keeps this safe.
        for (BankTransaction t : txns) {
            if (!t.isRequiresDocument() || matchedTxnIds.contains(t.getId()) || t.getAmount().signum() >= 0) {
                continue;
            }
            Invoice hit = firstInvoice(periodInvoices, inv ->
                    amountMatches(inv, t) && dateOk(inv, t) && nameMatches(inv.getSupplierName(), t));
            if (hit != null) {
                autoMatch(tenantId, t, hit, matchedTxnIds, periodInvoices);
            }
        }
        // Tier 3 — unique exact amount. After the stronger passes, when exactly one open invoice and
        // exactly one still-unmatched supplier debit share the same exact amount in the period, they
        // almost certainly correspond even if the payment's merchant descriptor shares no name with the
        // supplier (a common POS case: EMBER SOFTWARE billed as "REVISALPLUS", SELGROS as "SG150").
        // Requiring uniqueness on BOTH sides avoids ambiguous pairings (e.g. two payments at the same
        // amount); the resulting link is a suggestion the accountant can review or unlink.
        java.util.Map<BigDecimal, List<Invoice>> invByAmount = new java.util.HashMap<>();
        for (Invoice inv : periodInvoices) {
            invByAmount.computeIfAbsent(inv.getTotalAmount().stripTrailingZeros(),
                    k -> new java.util.ArrayList<>()).add(inv);
        }
        java.util.Map<BigDecimal, List<BankTransaction>> txnByAmount = new java.util.HashMap<>();
        for (BankTransaction t : txns) {
            if (t.isRequiresDocument() && !matchedTxnIds.contains(t.getId()) && t.getAmount().signum() < 0) {
                txnByAmount.computeIfAbsent(t.getAmount().abs().stripTrailingZeros(),
                        k -> new java.util.ArrayList<>()).add(t);
            }
        }
        for (var e : invByAmount.entrySet()) {
            List<Invoice> is = e.getValue();
            List<BankTransaction> ts = txnByAmount.get(e.getKey());
            if (is.size() == 1 && ts != null && ts.size() == 1 && dateOk(is.get(0), ts.get(0))) {
                autoMatch(tenantId, ts.get(0), is.get(0), matchedTxnIds, periodInvoices);
            }
        }
    }

    private Invoice firstInvoice(List<Invoice> invoices, java.util.function.Predicate<Invoice> p) {
        for (Invoice inv : invoices) {
            if (p.test(inv)) {
                return inv;
            }
        }
        return null;
    }

    private void autoMatch(UUID tenantId, BankTransaction t, Invoice inv,
                           java.util.Set<UUID> matchedTxnIds, List<Invoice> periodInvoices) {
        // Exact 1:1 match: the payment fully settles the invoice, so allocate its full total.
        matches.save(new TransactionInvoiceMatch(tenantId, t.getId(), inv.getId(), "AUTO", null,
                inv.getTotalAmount()));
        matchedTxnIds.add(t.getId());
        periodInvoices.remove(inv);
    }

    private boolean amountMatches(Invoice inv, BankTransaction t) {
        return inv.getTotalAmount().abs().subtract(t.getAmount().abs()).abs().compareTo(TOLERANCE) <= 0;
    }

    private boolean dateOk(Invoice inv, BankTransaction t) {
        return inv.getInvoiceDate() == null
                || !t.getTxnDate().isBefore(inv.getInvoiceDate().minusDays(DATE_BACK_TOLERANCE_DAYS));
    }

    // Non-identifying tokens (legal forms + the ubiquitous "romania") — a name match must rest on a more
    // distinctive word. Kept minimal on purpose: the exact-amount requirement already guards precision,
    // so over-stoplisting (e.g. "energie") would only drop real matches like E.ON.
    private static final java.util.Set<String> NAME_STOP = java.util.Set.of(
            "romania", "srl", "srld", "srls", "sa", "scs", "sca", "snc", "ifn", "pfa");

    /**
     * True when the transaction's counterparty text names the invoice's supplier: a distinctive supplier
     * token (≥ 4 letters, not a generic business word) appears in the transaction's partner name or
     * description. Diacritics- and punctuation-insensitive (so "MAXCODE TEAM S.R.L." matches
     * "MAXCODETEAM SRL", "SAGA Software" matches "EP*sagasoft.ro", "Kaufland Romania SCS" matches
     * "KAUFLAND 4700 CLUJ"). Paired with an exact amount, this is a safe auto-match.
     */
    private boolean nameMatches(String supplierName, BankTransaction t) {
        if (supplierName == null || supplierName.isBlank()) {
            return false;
        }
        String hay = normLetters((t.getPartnerName() == null ? "" : t.getPartnerName()) + " "
                + (t.getDescription() == null ? "" : t.getDescription())).replace(" ", "");
        for (String tok : normLetters(supplierName).split("\\s+")) {
            if (tok.length() >= 4 && !NAME_STOP.contains(tok) && hay.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    /** Lowercase, drop diacritics, and reduce to letters/digits + single spaces. */
    private static String normLetters(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
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
        // A wrong-party invoice/receipt (client CIF ≠ this company) belongs to someone else and must not
        // be associated with this company's transactions — even by a manual link.
        if (Boolean.TRUE.equals(inv.getWrongParty())) {
            throw new IllegalArgumentException(
                    "This document was issued to a different company (CIF mismatch) and cannot be matched here");
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

    /**
     * Non-trivial match proposals for the period the accountant is reconciling. Exact 1:1 in-period
     * matches are already auto-applied, so this surfaces what auto-match can't decide alone: cross-period
     * exacts, one payment that settles several invoices (SPLIT), and one invoice cleared by several of
     * this period's payments (INSTALLMENT). All grouped by supplier IBAN, date-guarded, and
     * non-overlapping (each open txn/invoice appears in at most one suggestion).
     */
    @Transactional(readOnly = true)
    public List<MatchSuggestion> suggestions(UUID companyId, java.time.LocalDate periodMonth) {
        List<OpenTxnView> openTxns = openTransactions(companyId, periodMonth, 0); // current period only
        List<OpenInvoiceView> openInvs = openInvoices(companyId, periodMonth, 18); // rolling window

        java.util.Map<String, List<OpenTxnView>> txnsByIban = new java.util.HashMap<>();
        for (OpenTxnView t : openTxns) {
            if (t.partnerIban() != null) {
                txnsByIban.computeIfAbsent(t.partnerIban(), k -> new java.util.ArrayList<>()).add(t);
            }
        }
        java.util.Map<String, List<OpenInvoiceView>> invsByIban = new java.util.HashMap<>();
        for (OpenInvoiceView i : openInvs) {
            // Never suggest a wrong-party invoice/receipt (client CIF ≠ this company) for matching.
            if (i.supplierIban() != null && i.remaining() != null && !Boolean.TRUE.equals(i.wrongParty())) {
                invsByIban.computeIfAbsent(i.supplierIban(), k -> new java.util.ArrayList<>()).add(i);
            }
        }

        java.util.Set<UUID> usedTxns = new java.util.HashSet<>();
        java.util.Set<UUID> usedInvs = new java.util.HashSet<>();
        List<MatchSuggestion> out = new java.util.ArrayList<>();

        for (String iban : txnsByIban.keySet()) {
            List<OpenInvoiceView> invs = invsByIban.getOrDefault(iban, List.of());
            if (invs.isEmpty()) {
                continue;
            }
            List<OpenTxnView> txns = txnsByIban.get(iban);

            // 1) EXACT cross-period: a payment whose remaining equals a single open invoice's remaining.
            for (OpenTxnView t : txns) {
                if (usedTxns.contains(t.id())) {
                    continue;
                }
                for (OpenInvoiceView i : invs) {
                    if (usedInvs.contains(i.invoiceId()) || !dateOk(t, i)) {
                        continue;
                    }
                    if (i.remaining().subtract(t.remaining()).abs().compareTo(TOLERANCE) <= 0) {
                        out.add(new MatchSuggestion("EXACT", List.of(link(t, i, i.remaining()))));
                        usedTxns.add(t.id());
                        usedInvs.add(i.invoiceId());
                        break;
                    }
                }
            }

            // 2) SPLIT: one payment settles a subset of open invoices summing to its remaining.
            for (OpenTxnView t : txns) {
                if (usedTxns.contains(t.id())) {
                    continue;
                }
                List<OpenInvoiceView> cand = invs.stream()
                        .filter(i -> !usedInvs.contains(i.invoiceId()) && dateOk(t, i)).toList();
                List<Integer> subset = subsetSum(cand.stream().map(OpenInvoiceView::remaining).toList(), t.remaining());
                if (subset != null) {
                    List<SuggestionLink> links = new java.util.ArrayList<>();
                    for (int idx : subset) {
                        OpenInvoiceView i = cand.get(idx);
                        links.add(link(t, i, i.remaining()));
                        usedInvs.add(i.invoiceId());
                    }
                    usedTxns.add(t.id());
                    out.add(new MatchSuggestion("SPLIT", links));
                }
            }

            // 3) INSTALLMENT: one invoice cleared by a subset of this period's payments.
            for (OpenInvoiceView i : invs) {
                if (usedInvs.contains(i.invoiceId())) {
                    continue;
                }
                List<OpenTxnView> cand = txns.stream()
                        .filter(t -> !usedTxns.contains(t.id()) && dateOk(t, i)).toList();
                List<Integer> subset = subsetSum(cand.stream().map(OpenTxnView::remaining).toList(), i.remaining());
                if (subset != null) {
                    List<SuggestionLink> links = new java.util.ArrayList<>();
                    for (int idx : subset) {
                        OpenTxnView t = cand.get(idx);
                        links.add(link(t, i, t.remaining()));
                        usedTxns.add(t.id());
                    }
                    usedInvs.add(i.invoiceId());
                    out.add(new MatchSuggestion("INSTALLMENT", links));
                }
            }
        }
        return out;
    }

    private SuggestionLink link(OpenTxnView t, OpenInvoiceView i, BigDecimal amount) {
        return new SuggestionLink(t.id(), t.txnDate(), t.partnerName(), t.amount(),
                i.invoiceId(), i.filename(), i.supplierName(), amount);
    }

    private boolean dateOk(OpenTxnView t, OpenInvoiceView i) {
        return i.invoiceDate() == null
                || !t.txnDate().isBefore(i.invoiceDate().minusDays(DATE_BACK_TOLERANCE_DAYS));
    }

    /**
     * First subset (size 2..4) of {@code vals} summing to {@code target} within tolerance, as indices.
     * Bounded brute force over up to 16 candidates; returns null when none (or too many candidates).
     */
    private List<Integer> subsetSum(List<BigDecimal> vals, BigDecimal target) {
        int n = vals.size();
        if (n < 2 || n > 16) {
            return null;
        }
        for (int mask = 1; mask < (1 << n); mask++) {
            int bits = Integer.bitCount(mask);
            if (bits < 2 || bits > 4) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < n; j++) {
                if ((mask & (1 << j)) != 0) {
                    sum = sum.add(vals.get(j));
                }
            }
            if (sum.subtract(target).abs().compareTo(TOLERANCE) <= 0) {
                List<Integer> idx = new java.util.ArrayList<>();
                for (int j = 0; j < n; j++) {
                    if ((mask & (1 << j)) != 0) {
                        idx.add(j);
                    }
                }
                return idx;
            }
        }
        return null;
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

    private TransactionRule matchRule(List<TransactionRule> learned, BankTransaction t) {
        String descNorm = ReconText.normalize(t.getDescription());
        return learned.stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
    }
}
