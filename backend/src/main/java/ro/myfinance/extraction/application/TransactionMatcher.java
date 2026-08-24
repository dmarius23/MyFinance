package ro.myfinance.extraction.application;

import static ro.myfinance.extraction.application.ReconThresholds.DATE_BACK_TOLERANCE_DAYS;
import static ro.myfinance.extraction.application.ReconThresholds.TOLERANCE;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.Invoice;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;

/**
 * Reconciles bank transactions against invoices/receipts: the 3-tier auto-matcher for a period and the
 * manual link/unlink allocations. Never matches a wrong-party document, caps allocations at both
 * remainings, and settles the strongest evidence (IBAN) first.
 */
@Service
@Transactional
public class TransactionMatcher {

    private final BankTransactionRepository transactions;
    private final CanonicalTransactions canonical;
    private final InvoiceRepository invoices;
    private final TransactionInvoiceMatchRepository matches;
    private final AuditRecorder audit;

    public TransactionMatcher(BankTransactionRepository transactions, CanonicalTransactions canonical,
                              InvoiceRepository invoices, TransactionInvoiceMatchRepository matches,
                              AuditRecorder audit) {
        this.transactions = transactions;
        this.canonical = canonical;
        this.invoices = invoices;
        this.matches = matches;
        this.audit = audit;
    }

    public void matchPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        // Match against the month's canonical (deduped) transactions — never the raw rows, so an invoice
        // can't be settled twice by the same transaction appearing in two overlapping files.
        List<BankTransaction> txns = canonical.forMonth(companyId, period);
        if (txns.isEmpty()) {
            return;
        }
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
}
