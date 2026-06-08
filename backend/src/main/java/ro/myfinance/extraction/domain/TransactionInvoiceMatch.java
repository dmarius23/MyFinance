package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionInvoiceMatch() {
    }

    public TransactionInvoiceMatch(UUID tenantId, UUID transactionId, UUID invoiceId, String source,
                                   UUID createdBy) {
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.invoiceId = invoiceId;
        this.source = source;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getSource() { return source; }
}
