package ro.myfinance.company.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.company.domain.Company;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    // Tenant scoping is enforced by RLS; CUI uniqueness is enforced per tenant in the DB.
    boolean existsByCui(String cui);

    // Used when editing a company's CUI: a clash with any OTHER company in the tenant is a conflict.
    boolean existsByCuiAndIdNot(String cui, UUID id);
}
