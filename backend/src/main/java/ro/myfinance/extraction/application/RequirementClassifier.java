package ro.myfinance.extraction.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionRuleRepository;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.DocCategory;
import ro.myfinance.extraction.domain.TransactionRule;

/**
 * Decides, per bank transaction, whether the client owes a supporting document — from the deterministic
 * {@link TransactionClassifier} base rules, the company's learned overrides ({@code transaction_rule}), and
 * the shareholder-account heuristic. Accountant overrides are recorded here as learned rules so the next
 * statement inherits them.
 */
@Service
@Transactional
public class RequirementClassifier {

    private final TransactionClassifier classifier;
    private final TransactionRuleRepository rules;
    private final BankTransactionRepository transactions;
    private final CompanyDirectory companies;
    private final AuditRecorder audit;

    public RequirementClassifier(TransactionClassifier classifier, TransactionRuleRepository rules,
                                 BankTransactionRepository transactions, CompanyDirectory companies,
                                 AuditRecorder audit) {
        this.classifier = classifier;
        this.rules = rules;
        this.transactions = transactions;
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
        applyShareholderAccountRule(txns, companyId);
    }

    /**
     * A counterparty account that receives dividend payments is the owner/shareholder's account; other
     * debits to that same account (e.g. a loan repayment "restituire creditare") are owner payouts, not
     * purchases, so they need no document. Shareholder accounts are gathered from the company's dividend
     * transactions across all statements. Accountant and learned-rule decisions are left untouched.
     */
    private void applyShareholderAccountRule(List<BankTransaction> txns, UUID companyId) {
        java.util.Set<String> shareholderIbans = new java.util.HashSet<>();
        java.util.function.Consumer<BankTransaction> collect = t -> {
            if (t.getCategory() == DocCategory.DIVIDEND && t.getPartnerIban() != null && !t.getPartnerIban().isBlank()) {
                shareholderIbans.add(t.getPartnerIban());
            }
        };
        transactions.findByCompanyId(companyId).forEach(collect); // persisted, across statements
        txns.forEach(collect);                                    // this statement, just (re)classified
        if (shareholderIbans.isEmpty()) {
            return;
        }
        for (BankTransaction t : txns) {
            if (t.getDecisionSource() == DecisionSource.ACCOUNTANT_SET
                    || t.getDecisionSource() == DecisionSource.LEARNED_RULE) {
                continue;
            }
            if (t.getAmount().signum() < 0 && t.isRequiresDocument()
                    && t.getPartnerIban() != null && shareholderIbans.contains(t.getPartnerIban())) {
                t.setRequiresDocument(false);
                t.setCategory(DocCategory.DIVIDEND);
            }
        }
    }

    /**
     * Accountant override: set the requirement on the transaction and remember it as a learned rule
     * (keyed by counterparty IBAN + normalized description). Returns the mutated transaction; the caller
     * builds its match view.
     */
    public BankTransaction applyOverride(UUID txnId, boolean requiresDocument, String reason) {
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

    private TransactionRule matchRule(List<TransactionRule> learned, BankTransaction t) {
        String descNorm = ReconText.normalize(t.getDescription());
        return learned.stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
    }
}
