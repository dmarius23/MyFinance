package ro.myfinance.company.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ro.myfinance.company.domain.Company;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    // Tenant scoping is enforced by RLS; CUI uniqueness is enforced per tenant in the DB.
    boolean existsByCui(String cui);

    // Used when editing a company's CUI: a clash with any OTHER company in the tenant is a conflict.
    boolean existsByCuiAndIdNot(String cui, UUID id);

    // Company legal name is also unique per tenant (case-insensitive) — for create + CSV import dedup.
    boolean existsByLegalNameIgnoreCase(String legalName);

    boolean existsByLegalNameIgnoreCaseAndIdNot(String legalName, UUID id);

    /**
     * Paged company search by name or CUI, ordered by name. Substring match, case- AND diacritic-insensitive
     * (Romanian ă â î ș ş ț ţ folded to a a i s s t t), so any part of the name matches. Empty {@code q}
     * returns all. RLS scopes results to the current tenant. {@code q} must be non-null (pass "" for all).
     */
    @Query(value = """
            SELECT * FROM company
            WHERE :q = ''
               OR translate(lower(legal_name), 'ăâîșşțţ', 'aaisstt') LIKE ('%' || translate(lower(:q), 'ăâîșşțţ', 'aaisstt') || '%')
               OR cui LIKE ('%' || :q || '%')
            ORDER BY legal_name
            """,
            countQuery = """
            SELECT count(*) FROM company
            WHERE :q = ''
               OR translate(lower(legal_name), 'ăâîșşțţ', 'aaisstt') LIKE ('%' || translate(lower(:q), 'ăâîșşțţ', 'aaisstt') || '%')
               OR cui LIKE ('%' || :q || '%')
            """,
            nativeQuery = true)
    Page<Company> search(@Param("q") String q, Pageable pageable);

    /** The ids of every company matching {@code q} (unpaged) — for server-side bulk actions over the filter. */
    @Query(value = """
            SELECT id FROM company
            WHERE :q = ''
               OR translate(lower(legal_name), 'ăâîșşțţ', 'aaisstt') LIKE ('%' || translate(lower(:q), 'ăâîșşțţ', 'aaisstt') || '%')
               OR cui LIKE ('%' || :q || '%')
            """, nativeQuery = true)
    List<UUID> searchIds(@Param("q") String q);
}
