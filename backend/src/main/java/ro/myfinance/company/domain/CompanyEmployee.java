package ro.myfinance.company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** MOD-03 — lightweight HR registry entry for a client company's employee. */
@Entity
@Table(name = "company_employee")
public class CompanyEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String name;

    private String role;

    @Column(name = "hired_on")
    private LocalDate hiredOn;

    @Column(name = "terminated_on")
    private LocalDate terminatedOn;

    @Column(nullable = false)
    private String status = "ACTIVE";

    protected CompanyEmployee() {
    }

    public CompanyEmployee(UUID tenantId, UUID companyId, String name, String role, LocalDate hiredOn) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.name = name;
        this.role = role;
        this.hiredOn = hiredOn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public LocalDate getHiredOn() {
        return hiredOn;
    }

    public LocalDate getTerminatedOn() {
        return terminatedOn;
    }

    public String getStatus() {
        return status;
    }
}
