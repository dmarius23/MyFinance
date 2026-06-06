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

    protected BankTransaction() {
    }

    public BankTransaction(UUID tenantId, UUID companyId, UUID statementId, LocalDate txnDate,
                           BigDecimal amount, TxnDirection direction, String partnerName,
                           String partnerIban, String description, String ref, BigDecimal balanceAfter) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.statementId = statementId;
        this.txnDate = txnDate;
        this.amount = amount;
        this.direction = direction;
        this.partnerName = partnerName;
        this.partnerIban = partnerIban;
        this.description = description;
        this.ref = ref;
        this.balanceAfter = balanceAfter;
    }

    public UUID getId() { return id; }
    public UUID getStatementId() { return statementId; }
    public LocalDate getTxnDate() { return txnDate; }
    public BigDecimal getAmount() { return amount; }
    public TxnDirection getDirection() { return direction; }
    public String getPartnerName() { return partnerName; }
    public String getPartnerIban() { return partnerIban; }
    public String getDescription() { return description; }
    public String getRef() { return ref; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}
