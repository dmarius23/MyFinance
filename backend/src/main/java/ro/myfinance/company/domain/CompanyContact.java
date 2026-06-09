package ro.myfinance.company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** MOD-03 — a contact person for a client company. */
@Entity
@Table(name = "company_contact")
public class CompanyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String name;

    private String email;
    private String phone;
    private String role;

    protected CompanyContact() {
    }

    public CompanyContact(UUID tenantId, UUID companyId, String name, String email, String phone, String role) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.role = role;
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

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getRole() {
        return role;
    }
}
