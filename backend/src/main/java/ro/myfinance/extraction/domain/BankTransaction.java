package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;

    @Column(name = "account_iban")
    private String accountIban;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnDirection direction;

    @Column(name = "partner_name")
    private String partnerName;

    @Column(name = "partner_iban")
    private String partnerIban;

    private String description;

    private String ref;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    @Column(name = "matched_document_id")
    private UUID matchedDocumentId;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source")
    private DecisionSource decisionSource;

    @Enumerated(EnumType.STRING)
    private DocCategory category;

    @Column(name = "override_reason")
    private String overrideReason;

    protected BankTransaction() {
    }

    public BankTransaction(UUID tenantId, UUID companyId, UUID statementId, String accountIban,
                           LocalDate txnDate, BigDecimal amount, TxnDirection direction,
                           String partnerName, String partnerIban, String description, String ref,
                           BigDecimal balanceAfter) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.statementId = statementId;
        this.accountIban = accountIban;
        this.txnDate = txnDate;
        this.amount = amount;
        this.direction = direction;
        this.partnerName = partnerName;
        this.partnerIban = partnerIban;
        this.description = description;
        this.ref = ref;
        this.balanceAfter = balanceAfter;
        this.requiresDocument = false;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStatementId() { return statementId; }
    public String getAccountIban() { return accountIban; }
    public LocalDate getTxnDate() { return txnDate; }
    public BigDecimal getAmount() { return amount; }
    public TxnDirection getDirection() { return direction; }
    public String getPartnerName() { return partnerName; }
    public String getPartnerIban() { return partnerIban; }
    public String getDescription() { return description; }
    public String getRef() { return ref; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getMatchedDocumentId() { return matchedDocumentId; }
    public boolean isRequiresDocument() { return requiresDocument; }
    public DecisionSource getDecisionSource() { return decisionSource; }
    public DocCategory getCategory() { return category; }
    public String getOverrideReason() { return overrideReason; }

    public void setRequiresDocument(boolean requiresDocument) { this.requiresDocument = requiresDocument; }
    public void setDecisionSource(DecisionSource decisionSource) { this.decisionSource = decisionSource; }
    public void setCategory(DocCategory category) { this.category = category; }
    public void setOverrideReason(String overrideReason) { this.overrideReason = overrideReason; }
}
