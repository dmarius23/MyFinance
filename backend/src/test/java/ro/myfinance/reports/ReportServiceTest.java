package ro.myfinance.reports;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.reports.adapter.persistence.ReportEmailRepository;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.application.TrialBalanceExtractor;
import ro.myfinance.reports.domain.ReportSnapshot;

/**
 * Guards the ingest party-check: a trial balance whose embedded CUI belongs to a different company must
 * never produce a report snapshot (so no report/charts/PDF — and nothing the rep could see). Runs on the
 * real (gitignored, PII) balance fixture for company CUI 49443957; self-skips when absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    private static final LocalDate MONTH = LocalDate.of(2026, 3, 1);

    @Mock ReportSnapshotRepository snapshots;
    @Mock ReportEmailRepository emails;
    @Mock CompanyRepository companies;

    private ReportService service() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules(); // JSR-310 for LocalDate
        return new ReportService(snapshots, emails, new TrialBalanceExtractor(), mapper, companies);
    }

    private byte[] fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/reports/balanta_2026_03.pdf")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private Company companyWithCui(String cui) {
        Company c = mock(Company.class);
        when(c.getCui()).thenReturn(cui);
        return c;
    }

    @Test
    void wrongPartyTrialBalanceProducesNoReportSnapshot() throws Exception {
        byte[] tb = fixture();
        assumeTrue(tb != null, "fixture missing: balanta_2026_03.pdf (PII PDF, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company differentParty = companyWithCui("12345678"); // different CUI
        when(companies.findById(companyId)).thenReturn(Optional.of(differentParty));
        when(snapshots.findByCompanyIdAndPeriodMonth(eq(companyId), any())).thenReturn(Optional.empty());

        service().ingest(companyId, MONTH, UUID.randomUUID(), tb);

        verify(snapshots, never()).save(any());
    }

    @Test
    void matchingTrialBalanceIsIngested() throws Exception {
        byte[] tb = fixture();
        assumeTrue(tb != null, "fixture missing: balanta_2026_03.pdf (PII PDF, gitignored)");

        UUID companyId = UUID.randomUUID();
        Company sameParty = companyWithCui("49443957"); // fixture's CUI
        when(companies.findById(companyId)).thenReturn(Optional.of(sameParty));
        when(snapshots.findByCompanyIdAndPeriodMonth(eq(companyId), any())).thenReturn(Optional.empty());

        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), null, null));
        try {
            service().ingest(companyId, MONTH, UUID.randomUUID(), tb);
        } finally {
            TenantContext.clear();
        }

        verify(snapshots).save(any(ReportSnapshot.class));
    }
}
