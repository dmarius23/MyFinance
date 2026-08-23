package ro.myfinance.company.application;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

/**
 * Client company management. Tenant id always comes from {@link TenantContext}, never the client.
 * CUI is unique per tenant.
 */
@Service
@Transactional
public class CompanyService {

    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) {
        this.companies = companies;
    }

    /** Max page size a client can request; larger requests are clamped to this. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Full list — used by pickers/dropdowns across modules that need every company at once. */
    @Transactional(readOnly = true)
    public List<Company> list() {
        return companies.findAll();
    }

    /**
     * Paged list for the Companies directory + module screens (infinite scroll), optionally filtered by a
     * fuzzy company search ({@code q}: name or CUI, substring, diacritic-insensitive). The search query
     * carries its own name ordering; RLS scopes rows to the tenant, including the count query.
     */
    @Transactional(readOnly = true)
    public Page<Company> listPage(String q, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return companies.search(q == null ? "" : q.trim(), PageRequest.of(safePage, safeSize));
    }

    @Transactional(readOnly = true)
    public Company get(UUID id) {
        return companies.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found: " + id));
    }

    public Company create(String legalName, String cui, String entityType, String locality,
                          String vatStatus, String vatPeriod, String taxRegime, Boolean hasEmployees,
                          UUID responsibleUserId) {
        if (companies.existsByCui(cui)) {
            throw new ConflictException("A company with CUI " + cui + " already exists in this tenant");
        }
        Company company = new Company(currentTenant(), legalName, cui);
        company.setEntityType(entityType);
        company.setLocality(locality);
        company.setVatStatus(vatStatus);
        company.setVatPeriod(vatPeriod);
        company.setTaxRegime(taxRegime);
        company.setHasEmployees(hasEmployees);
        company.setResponsibleUserId(responsibleUserId);
        return companies.save(company);
    }

    public Company update(UUID id, String cui, String legalName, String entityType, String locality,
                          String vatStatus, String vatPeriod, String taxRegime, Boolean hasEmployees,
                          UUID responsibleUserId) {
        Company company = get(id);
        if (cui != null && !cui.equals(company.getCui())) {
            if (cui.isBlank()) {
                throw new IllegalArgumentException("CUI cannot be blank");
            }
            if (companies.existsByCuiAndIdNot(cui, id)) {
                throw new ConflictException("A company with CUI " + cui + " already exists in this tenant");
            }
            company.setCui(cui);
        }
        if (legalName != null) {
            company.setLegalName(legalName);
        }
        company.setEntityType(entityType);
        company.setLocality(locality);
        company.setVatStatus(vatStatus);
        company.setVatPeriod(vatPeriod);
        company.setTaxRegime(taxRegime);
        company.setHasEmployees(hasEmployees);
        company.setResponsibleUserId(responsibleUserId);
        return company;
    }

    public Company setStatus(UUID id, CompanyStatus status) {
        Company company = get(id);
        company.setStatus(status);
        return company;
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
