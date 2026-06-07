package ro.myfinance.extraction.application;

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
import ro.myfinance.extraction.adapter.persistence.TransactionRuleRepository;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.TransactionRule;

/** Document-requirement classification, accountant overrides (with learned rules), completeness. */
@Service
@Transactional
public class ReconciliationService {

    public enum Completeness { NOT_STARTED, PARTIAL, COMPLETE }

    public record CompanyCompleteness(UUID companyId, Completeness completeness) {
    }

    private final TransactionClassifier classifier;
    private final TransactionRuleRepository rules;
    private final BankTransactionRepository transactions;
    private final BankStatementRepository statements;
    private final CompanyRepository companies;
    private final AuditRecorder audit;

    public ReconciliationService(TransactionClassifier classifier, TransactionRuleRepository rules,
                                 BankTransactionRepository transactions, BankStatementRepository statements,
                                 CompanyRepository companies, AuditRecorder audit) {
        this.classifier = classifier;
        this.rules = rules;
        this.transactions = transactions;
        this.statements = statements;
        this.companies = companies;
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

    /** Accountant override: set the requirement, remember it as a learned rule. */
    public BankTransaction setRequirement(UUID txnId, boolean requiresDocument, String reason) {
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
        return t;
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
            boolean missing = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds).stream()
                    .anyMatch(t -> t.isRequiresDocument() && t.getMatchedDocumentId() == null);
            out.add(new CompanyCompleteness(e.getKey(), missing ? Completeness.PARTIAL : Completeness.COMPLETE));
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
