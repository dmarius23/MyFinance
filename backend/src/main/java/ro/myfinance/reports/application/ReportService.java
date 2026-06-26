package ro.myfinance.reports.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.reports.adapter.persistence.ReportEmailRepository;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.ReportSnapshot;
import ro.myfinance.reports.domain.TrialBalanceData;

/**
 * MOD-06 Reports. Ingests an uploaded trial balance (extract → compute → store the {@link ReportData}
 * JSON, one row per company/month; re-upload bumps the version), and serves the report, the monthly
 * list, and the revenue/profit trend across months. Tenant-scoped via RLS.
 */
@Service
@Transactional
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportSnapshotRepository snapshots;
    private final ReportEmailRepository emails;
    private final TrialBalanceExtractor extractor;
    private final ObjectMapper json;
    private final ro.myfinance.company.adapter.persistence.CompanyRepository companies;

    public ReportService(ReportSnapshotRepository snapshots, ReportEmailRepository emails,
                         TrialBalanceExtractor extractor, ObjectMapper json,
                         ro.myfinance.company.adapter.persistence.CompanyRepository companies) {
        this.snapshots = snapshots;
        this.emails = emails;
        this.extractor = extractor;
        this.json = json;
        this.companies = companies;
    }

    /** Per-company report status for the monthly list. */
    public record ReportRow(UUID companyId, Instant uploadedAt, int version, boolean balanced,
                            Instant lastSentAt, int sentCount) {
    }

    /** One point on the revenue/profit trend. */
    public record TrendPoint(LocalDate periodMonth, java.math.BigDecimal revenue,
                             java.math.BigDecimal expenses, java.math.BigDecimal netProfit) {
    }

    /** Extract + compute + store the report for an uploaded trial balance (re-upload bumps version). */
    public void ingest(UUID companyId, LocalDate periodMonth, UUID documentId, byte[] bytes) {
        TrialBalanceData tb = extractor.extract(bytes);
        // Defence in depth: a trial balance whose embedded CUI clearly belongs to a different company must
        // never produce a report/charts (the rep would otherwise see another company's figures). Upload
        // already rejects wrong-party trial balances; this guards the residual unverifiable-at-upload case.
        if (differentCui(tb.cui(), companies.findById(companyId).map(c -> c.getCui()).orElse(null))) {
            log.warn("Skipping report ingest for doc {} (company {}): trial-balance CUI {} != company CUI",
                    documentId, companyId, tb.cui());
            return;
        }
        ReportData data = ReportCalculator.compute(tb);
        String body = write(data);
        LocalDate month = periodMonth.withDayOfMonth(1);
        snapshots.findByCompanyIdAndPeriodMonth(companyId, month).ifPresentOrElse(
                s -> s.replace(documentId, data.balanced(), body),
                () -> snapshots.save(new ReportSnapshot(TenantContext.tenantId().orElseThrow(),
                        companyId, month, documentId, data.balanced(), body)));
    }

    /** Wrong party only when both CUIs are known and their bare digits differ. */
    private static boolean differentCui(String tbCui, String companyCui) {
        String a = tbCui == null ? "" : tbCui.replaceAll("\\D", "");
        String b = companyCui == null ? "" : companyCui.replaceAll("\\D", "");
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    /** The computed report for a company/period. */
    @Transactional(readOnly = true)
    public ReportData report(UUID companyId, LocalDate periodMonth) {
        ReportSnapshot s = snapshots.findByCompanyIdAndPeriodMonth(companyId, periodMonth.withDayOfMonth(1))
                .orElseThrow(() -> new NotFoundException("No report for company " + companyId));
        return read(s.getReportJson());
    }

    /** Per-company rows for the period (report uploaded? + email last-sent). */
    @Transactional(readOnly = true)
    public List<ReportRow> summary(LocalDate periodMonth) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        Map<UUID, ReportSnapshot> byCompany = new LinkedHashMap<>();
        for (ReportSnapshot s : snapshots.findByPeriodMonth(month)) {
            byCompany.put(s.getCompanyId(), s);
        }
        Map<UUID, List<ro.myfinance.reports.domain.ReportEmail>> emailsByCompany = new LinkedHashMap<>();
        for (var e : emails.findByPeriodMonthOrderBySentAtDesc(month)) {
            emailsByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        ids.addAll(byCompany.keySet());
        ids.addAll(emailsByCompany.keySet());

        List<ReportRow> out = new ArrayList<>();
        for (UUID companyId : ids) {
            ReportSnapshot s = byCompany.get(companyId);
            var es = emailsByCompany.getOrDefault(companyId, List.of());
            out.add(new ReportRow(companyId,
                    s == null ? null : s.getUpdatedAt(),
                    s == null ? 0 : s.getVersion(),
                    s != null && s.isBalanced(),
                    es.isEmpty() ? null : es.get(0).getSentAt(),
                    es.size()));
        }
        return out;
    }

    /** Revenue/expenses/net-profit for the last {@code months} periods up to and including the given one. */
    @Transactional(readOnly = true)
    public List<TrendPoint> trend(UUID companyId, LocalDate periodMonth, int months) {
        LocalDate to = periodMonth.withDayOfMonth(1);
        LocalDate from = to.minusMonths(Math.max(0, months - 1L));
        List<TrendPoint> out = new ArrayList<>();
        for (ReportSnapshot s : snapshots.findByCompanyIdAndPeriodMonthLessThanEqualOrderByPeriodMonthAsc(companyId, to)) {
            if (s.getPeriodMonth().isBefore(from)) {
                continue;
            }
            ReportData d = read(s.getReportJson());
            out.add(new TrendPoint(s.getPeriodMonth(), d.profitLoss().revenue(),
                    d.profitLoss().operatingExpenses(), d.profitLoss().netProfit()));
        }
        return out;
    }

    private String write(ReportData data) {
        try {
            return json.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize report", e);
        }
    }

    private ReportData read(String body) {
        try {
            return json.readValue(body, ReportData.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to read stored report", e);
        }
    }
}
