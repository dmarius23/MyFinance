package ro.myfinance.taxpayments.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.domain.ObligationLine;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/**
 * One-off, idempotent backfill: declarations stored before the {@code obligations} column existed have it
 * NULL, so the list shows only their document total (no per-creanță code/label). On startup — when
 * {@code myfinance.taxpayments.backfill-obligations=true} (the local profile) — re-extract each such
 * declaration's XML and populate its obligations. Once populated, later startups find nothing to do.
 *
 * <p>Enumeration is cross-tenant via the admin datasource; the actual re-extraction runs per declaration
 * under that declaration's {@link TenantContext} (set here, before the transactional call, so the
 * RLS-scoped connection is bound correctly), reading the document and writing through normal JPA.
 */
@Component
@ConditionalOnProperty(name = "myfinance.taxpayments.backfill-obligations", havingValue = "true")
public class TaxObligationBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaxObligationBackfill.class);

    private final JdbcTemplate adminJdbc;
    private final ObligationBackfillTx tx;

    public TaxObligationBackfill(@Qualifier("adminJdbcTemplate") JdbcTemplate adminJdbc, ObligationBackfillTx tx) {
        this.adminJdbc = adminJdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = adminJdbc.queryForList(
                "select id, tenant_id from tax_declaration where obligations is null");
        if (rows.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            UUID tenant = (UUID) r.get("tenant_id");
            TenantContext.set(new TenantContext.Identity(tenant, tenant, Role.EMPLOYEE, null));
            try {
                if (tx.backfill(id)) {
                    ok++;
                }
            } catch (RuntimeException e) {
                log.warn("Obligation backfill failed for declaration {}", id, e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Backfilled fiscal obligations for {}/{} declarations", ok, rows.size());
    }
}

/** The transactional unit — a separate bean so the {@code @Transactional} proxy applies to each call. */
@Service
class ObligationBackfillTx {

    private final TaxDeclarationRepository declarations;
    private final DocumentService documents;
    private final AnafDeclarationExtractor extractor;

    ObligationBackfillTx(TaxDeclarationRepository declarations, DocumentService documents,
                         AnafDeclarationExtractor extractor) {
        this.declarations = declarations;
        this.documents = documents;
        this.extractor = extractor;
    }

    @Transactional
    public boolean backfill(UUID declarationId) {
        TaxDeclaration d = declarations.findById(declarationId).orElse(null);
        if (d == null || d.getObligations() != null) {
            return false;
        }
        ParsedDeclaration pd = extractor.extract(documents.getContent(d.getDocumentId()).bytes());
        d.setObligations(pd.obligations().stream()
                .map(o -> new ObligationLine(o.codOblig(), o.amount())).toList());
        declarations.save(d);
        return true;
    }
}
