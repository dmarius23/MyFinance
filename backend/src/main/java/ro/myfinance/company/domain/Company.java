package ro.myfinance.company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** MOD-03 — a client company managed by the tenant firm. CUI is unique per tenant. */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "entity_type")
    private String entityType;

    @Column(nullable = false)
    private String cui;

    @Column(name = "reg_no")
    private String regNo;

    private String address;
    private String locality;

    @Column(name = "vat_status")
    private String vatStatus;

    @Column(name = "vat_period")
    private String vatPeriod;

    @Column(name = "tax_regime")
    private String taxRegime;

    @Column(name = "has_employees")
    private Boolean hasEmployees;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Company() {
    }

    public Company(UUID tenantId, String legalName, String cui) {
        this.tenantId = tenantId;
        this.legalName = legalName;
        this.cui = cui;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getCui() {
        return cui;
    }

    public void setCui(String cui) {
        this.cui = cui;
    }

    public String getRegNo() {
        return regNo;
    }

    public void setRegNo(String regNo) {
        this.regNo = regNo;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    public String getVatStatus() {
        return vatStatus;
    }

    public void setVatStatus(String vatStatus) {
        this.vatStatus = vatStatus;
    }

    public String getVatPeriod() {
        return vatPeriod;
    }

    public void setVatPeriod(String vatPeriod) {
        this.vatPeriod = vatPeriod;
    }

    public String getTaxRegime() {
        return taxRegime;
    }

    public void setTaxRegime(String taxRegime) {
        this.taxRegime = taxRegime;
    }

    public Boolean getHasEmployees() {
        return hasEmployees;
    }

    public void setHasEmployees(Boolean hasEmployees) {
        this.hasEmployees = hasEmployees;
    }

    public UUID getResponsibleUserId() {
        return responsibleUserId;
    }

    public void setResponsibleUserId(UUID responsibleUserId) {
        this.responsibleUserId = responsibleUserId;
    }

    public CompanyStatus getStatus() {
        return status;
    }

    public void setStatus(CompanyStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
