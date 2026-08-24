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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "bank_statement")
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "bank_code")
    private String bankCode;

    @Column(name = "account_iban")
    private String accountIban;

    @Column(name = "opening_balance")
    private BigDecimal openingBalance;

    @Column(name = "closing_balance")
    private BigDecimal closingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementStatus status;

    @Column(name = "cross_check_ok", nullable = false)
    private boolean crossCheckOk;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    /** The file's real covered range (earliest/latest transaction date) — a statement may span several
     *  months. Used to list the file under every month it touches. Null when the file has no transactions. */
    @Column(name = "first_txn_date")
    private LocalDate firstTxnDate;

    @Column(name = "last_txn_date")
    private LocalDate lastTxnDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BankStatement() {
    }

    public BankStatement(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth,
                         String bankCode, String accountIban, BigDecimal openingBalance,
                         BigDecimal closingBalance, StatementStatus status, boolean crossCheckOk,
                         int txnCount) {
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.bankCode = bankCode;
        this.accountIban = accountIban;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.status = status;
        this.crossCheckOk = crossCheckOk;
        this.txnCount = txnCount;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getBankCode() { return bankCode; }
    public String getAccountIban() { return accountIban; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public BigDecimal getClosingBalance() { return closingBalance; }
    public StatementStatus getStatus() { return status; }
    public boolean isCrossCheckOk() { return crossCheckOk; }
    public int getTxnCount() { return txnCount; }
    public LocalDate getFirstTxnDate() { return firstTxnDate; }
    public LocalDate getLastTxnDate() { return lastTxnDate; }

    /** Record the file's covered range (earliest/latest transaction date). */
    public void setRange(LocalDate firstTxnDate, LocalDate lastTxnDate) {
        this.firstTxnDate = firstTxnDate;
        this.lastTxnDate = lastTxnDate;
    }
}
