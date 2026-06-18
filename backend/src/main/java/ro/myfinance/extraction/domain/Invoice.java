package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "invoice")
public class Invoice {

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

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "supplier_iban")
    private String supplierIban;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "issuer_cif")
    private String issuerCif;

    @Column(name = "client_cif")
    private String clientCif;

    @Column(name = "receipt_number")
    private String receiptNumber;

    @Column(nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invoice() {
    }

    public Invoice(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth,
                   String supplierName, String supplierIban, BigDecimal totalAmount,
                   LocalDate invoiceDate, String originalFilename, String status) {
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.supplierName = supplierName;
        this.supplierIban = supplierIban;
        this.totalAmount = totalAmount;
        this.invoiceDate = invoiceDate;
        this.originalFilename = originalFilename;
        this.status = status;
    }

    /** Refresh the extracted fields in place (re-scan), preserving id/document_id and any matches. */
    public void updateExtraction(String supplierName, String supplierIban, BigDecimal totalAmount,
                                 LocalDate invoiceDate, String originalFilename, String status) {
        this.supplierName = supplierName;
        this.supplierIban = supplierIban;
        this.totalAmount = totalAmount;
        this.invoiceDate = invoiceDate;
        this.originalFilename = originalFilename;
        this.status = status;
    }

    /** Receipt-specific identifiers (null for invoices). */
    public void updateReceiptFields(String issuerCif, String clientCif, String receiptNumber) {
        this.issuerCif = issuerCif;
        this.clientCif = clientCif;
        this.receiptNumber = receiptNumber;
    }

    public UUID getId() { return id; }
    public String getIssuerCif() { return issuerCif; }
    public String getClientCif() { return clientCif; }
    public String getReceiptNumber() { return receiptNumber; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getSupplierName() { return supplierName; }
    public String getSupplierIban() { return supplierIban; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStatus() { return status; }
}
