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
                                     String supplierName) {
    }

    public record TxnWithMatches(BankTransaction txn, java.util.List<MatchedInvoiceView> invoices) {
    }

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

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
            List<UUID> txnIds = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds)
                    .stream().filter(BankTransaction::isRequiresDocument).map(BankTransaction::getId).toList();
            boolean missing;
            if (txnIds.isEmpty()) {
                missing = false;
            } else {
                java.util.Set<UUID> matchedIds = matches.findByTransactionIdIn(txnIds).stream()
                        .map(TransactionInvoiceMatch::getTransactionId).collect(java.util.stream.Collectors.toSet());
                missing = txnIds.stream().anyMatch(id -> !matchedIds.contains(id));
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
                boolean dateOk = inv.getInvoiceDate() == null || !t.getTxnDate().isBefore(inv.getInvoiceDate());
                if (ibanOk && amtOk && dateOk) {
                    hit = inv;
                    break;
                }
            }
            if (hit != null) {
                matches.save(new TransactionInvoiceMatch(tenantId, t.getId(), hit.getId(), "AUTO", null));
                matchedTxnIds.add(t.getId());
                periodInvoices.remove(hit);
            }
        }
    }

    public void link(UUID companyId, UUID txnId, UUID invoiceId) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        if (!t.getCompanyId().equals(companyId) || !inv.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Not found in company " + companyId);
        }
        if (inv.getInvoiceDate() != null && t.getTxnDate().isBefore(inv.getInvoiceDate())) {
            throw new IllegalArgumentException("Transaction date is before the invoice date");
        }
        if (!matches.existsByTransactionIdAndInvoiceId(txnId, invoiceId)) {
            UUID tenantId = TenantContext.tenantId().orElseThrow();
            UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
            matches.save(new TransactionInvoiceMatch(tenantId, txnId, invoiceId, "MANUAL", userId));
            audit.record("TXN_INVOICE_LINKED", "bank_transaction", txnId);
        }
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
                        .add(new MatchedInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                                i.getTotalAmount(), i.getInvoiceDate(), i.getSupplierName()));
            }
        }
        return txns.stream()
                .map(t -> new TxnWithMatches(t, byTxn.getOrDefault(t.getId(), List.of())))
                .toList();
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
                out.add(new MatchedInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                        i.getTotalAmount(), i.getInvoiceDate(), i.getSupplierName()));
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
