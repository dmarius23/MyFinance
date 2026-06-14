package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "transaction_invoice_match")
public class TransactionInvoiceMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String source;

    /** How much of the transaction this link applies to the invoice (payment allocation). */
    @Column(name = "allocated_amount", nullable = false)
    private BigDecimal allocatedAmount;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionInvoiceMatch() {
    }

    public TransactionInvoiceMatch(UUID tenantId, UUID transactionId, UUID invoiceId, String source,
                                   UUID createdBy, BigDecimal allocatedAmount) {
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.invoiceId = invoiceId;
        this.source = source;
        this.createdBy = createdBy;
        this.allocatedAmount = allocatedAmount;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getSource() { return source; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
}
