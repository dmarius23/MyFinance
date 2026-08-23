package ro.myfinance.company.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;

/**
 * Read-only directory of client companies, exposed to other modules so they don't reach into the
 * {@code company} module's persistence adapter. All lookups are RLS-scoped to the current tenant.
 * Write operations stay in {@link CompanyService}.
 */
@Service
@Transactional(readOnly = true)
public class CompanyDirectory {

    private final CompanyRepository companies;

    public CompanyDirectory(CompanyRepository companies) {
        this.companies = companies;
    }

    /** A single company by id, if it exists in the current tenant. */
    public Optional<Company> findById(UUID id) {
        return companies.findById(id);
    }

    /** All companies in the current tenant. */
    public List<Company> findAll() {
        return companies.findAll();
    }

    /** A page of companies matching {@code q} (name or CUI, substring, diacritic-insensitive), name-sorted. */
    public org.springframework.data.domain.Page<Company> search(
            String q, org.springframework.data.domain.Pageable pageable) {
        return companies.search(q == null ? "" : q.trim(), pageable);
    }

    /** The ids of all companies matching {@code q} — for server-side bulk actions over the whole filter. */
    public List<UUID> searchIds(String q) {
        return companies.searchIds(q == null ? "" : q.trim());
    }

    /** The companies with the given ids that exist in the current tenant. */
    public List<Company> findAllById(Iterable<UUID> ids) {
        return companies.findAllById(ids);
    }
}
