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
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
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
    private final EmailHistoryRepository emails;
    private final TrialBalanceExtractor extractor;
    private final ObjectMapper json;
    private final ro.myfinance.company.adapter.persistence.CompanyRepository companies;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public ReportService(ReportSnapshotRepository snapshots, EmailHistoryRepository emails,
                         TrialBalanceExtractor extractor, ObjectMapper json,
                         ro.myfinance.company.adapter.persistence.CompanyRepository companies,
                         DocumentRepository documents, DocumentStorage storage,
                         ro.myfinance.notifications.application.NotificationService notifications) {
        this.snapshots = snapshots;
        this.emails = emails;
        this.extractor = extractor;
        this.json = json;
        this.companies = companies;
        this.documents = documents;
        this.storage = storage;
        this.notifications = notifications;
    }

    /** Per-company report status for the monthly list. */
    public record ReportRow(UUID companyId, Instant uploadedAt, int version, boolean balanced,
                            Instant lastSentAt, int sentCount) {
    }

    /**
     * One point on the revenue/profit trend. Historical points have {@code projected == false} and null
     * bands; forecast points have {@code projected == true} and a confidence band on the two charted
     * lines (revenue, net profit) — bands are null when too few points exist to estimate spread. A
     * forecast is a <b>non-authoritative estimate</b>: it is never stored and never used as a money figure.
     */
    public record TrendPoint(LocalDate periodMonth, java.math.BigDecimal revenue,
                             java.math.BigDecimal expenses, java.math.BigDecimal netProfit,
                             boolean projected,
                             java.math.BigDecimal revenueLow, java.math.BigDecimal revenueHigh,
                             java.math.BigDecimal netProfitLow, java.math.BigDecimal netProfitHigh) {

        /** A historical (actual) point — no projection, no band. */
        public static TrendPoint actual(LocalDate periodMonth, java.math.BigDecimal revenue,
                                        java.math.BigDecimal expenses, java.math.BigDecimal netProfit) {
            return new TrendPoint(periodMonth, revenue, expenses, netProfit, false, null, null, null, null);
        }
    }

    /** Extract + compute + store the report for an uploaded trial balance (re-upload bumps version). */
    public void ingest(UUID companyId, LocalDate periodMonth, UUID documentId, byte[] bytes) {
        TrialBalanceData tb = extractor.extract(bytes);
        String companyCui = companies.findById(companyId).map(c -> c.getCui()).orElse(null);

        // Guard 1 — wrong company: the CUI embedded in the trial balance must match the company it is
        // filed under. Both CUIs must be present and their digits must match. If either cannot be
        // extracted (scanned PDF, no text) we skip this check conservatively — the upload-time
        // verifyBelongsToCompany already rejects the hard cases.
        if (differentCui(tb.cui(), companyCui)) {
            log.warn("Skipping report ingest for doc {} (company {}): trial-balance CUI '{}' != company CUI '{}'",
                    documentId, companyId, tb.cui(), companyCui);
            return;
        }

        // Guard 2 — wrong period: the date range printed in the trial-balance header must fall in the
        // same month as the accounting slot it was uploaded into. If the PDF has no parseable date range
        // (tb.periodStart() == null) we refuse to ingest rather than silently accepting a document whose
        // period is unknown — a trial balance without a readable period cannot be verified.
        LocalDate storedMonth = periodMonth.withDayOfMonth(1);
        if (tb.periodStart() == null) {
            log.warn("Skipping report ingest for doc {} (company {}): trial-balance period not parseable",
                    documentId, companyId);
            return;
        }
        LocalDate tbMonth = tb.periodStart().withDayOfMonth(1);
        if (!tbMonth.equals(storedMonth)) {
            log.warn("Skipping report ingest for doc {} (company {}): TB period {} != stored period {}",
                    documentId, companyId, tbMonth, storedMonth);
            return;
        }

        ReportData data = ReportCalculator.compute(tb);
        String body = write(data);
        snapshots.findByCompanyIdAndPeriodMonth(companyId, storedMonth).ifPresentOrElse(
                s -> s.replace(documentId, data.balanced(), body, tbMonth),
                () -> snapshots.save(new ReportSnapshot(TenantContext.tenantId().orElseThrow(),
                        companyId, storedMonth, documentId, data.balanced(), body, tbMonth)));

        notifications.notifyCompanyReps(companyId, "BALANCE_READY", "Balanță disponibilă",
                "Balanța pentru luna " + ReportEmailBuilder.monthYear(storedMonth) + " este disponibilă în aplicație.");
    }

    /** Wrong party only when both CUIs are known and their bare digits differ. */
    private static boolean differentCui(String tbCui, String companyCui) {
        String a = tbCui == null ? "" : tbCui.replaceAll("\\D", "");
        String b = companyCui == null ? "" : companyCui.replaceAll("\\D", "");
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    /** The computed report for a company/period. */
    @Transactional
    public ReportData report(UUID companyId, LocalDate periodMonth) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        ReportSnapshot s = snapshots.findByCompanyIdAndPeriodMonth(companyId, month)
                .orElseThrow(() -> new NotFoundException("No report for company " + companyId));

        // Determine the period the trial-balance PDF actually covers. Snapshots created after V29
        // have this stored directly. Legacy snapshots (content_period IS NULL) are detected lazily
        // by re-parsing the source document once — the result is cached so it never happens again.
        LocalDate contentPeriod = s.getContentPeriod();
        if (contentPeriod == null && s.getDocumentId() != null) {
            contentPeriod = detectAndCacheContentPeriod(s);
        }

        // If the PDF content belongs to a different month than the slot it was uploaded into,
        // this snapshot is invalid for this period — return 404 instead of wrong-month data.
        if (contentPeriod != null && !contentPeriod.equals(month)) {
            throw new NotFoundException("No report for company " + companyId);
        }
        return read(s.getReportJson());
    }

    /**
     * Re-parse the source document to detect its own period and cache it on the snapshot.
     * Called at most once per legacy row (before the content_period column existed).
     * Returns null if the document is gone or the PDF has no parseable date range.
     */
    private LocalDate detectAndCacheContentPeriod(ReportSnapshot s) {
        return documents.findById(s.getDocumentId()).map(doc -> {
            try {
                TrialBalanceData tb = extractor.extract(storage.retrieve(doc.getStorageKey()));
                LocalDate cp = tb.periodStart() != null ? tb.periodStart().withDayOfMonth(1) : null;
                s.setContentPeriod(cp); // cached — never re-parsed again
                return cp;
            } catch (Exception e) {
                log.warn("Could not detect content period for snapshot {} (doc {})", s.getId(), s.getDocumentId(), e);
                return null;
            }
        }).orElse(null);
    }

    /** Per-company rows for the period (report uploaded? + email last-sent). */
    @Transactional(readOnly = true)
    public List<ReportRow> summary(LocalDate periodMonth) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        Map<UUID, ReportSnapshot> byCompany = new LinkedHashMap<>();
        for (ReportSnapshot s : snapshots.findByPeriodMonth(month)) {
            // Exclude snapshots whose content_period is known and belongs to a different month —
            // the accountant should not see a ✓ for a report that isn't actually for this period.
            LocalDate cp = s.getContentPeriod();
            if (cp != null && !cp.equals(month)) {
                continue;
            }
            byCompany.put(s.getCompanyId(), s);
        }
        Map<UUID, List<ro.myfinance.common.email.EmailHistory>> emailsByCompany = new LinkedHashMap<>();
        for (var e : emails.findByKindAndPeriodMonthOrderBySentAtDesc(EmailKind.REPORT, month)) {
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

    /**
     * Revenue/expenses/net-profit for the last {@code months} periods up to and including the given one,
     * optionally followed by {@code forecast} projected points (a simple trend estimate — see
     * {@link TrendForecaster}). Projected points are computed per request and never persisted.
     */
    @Transactional(readOnly = true)
    public List<TrendPoint> trend(UUID companyId, LocalDate periodMonth, int months, int forecast) {
        LocalDate to = periodMonth.withDayOfMonth(1);
        LocalDate from = to.minusMonths(Math.max(0, months - 1L));
        List<TrendPoint> out = new ArrayList<>();
        for (ReportSnapshot s : snapshots.findByCompanyIdAndPeriodMonthLessThanEqualOrderByPeriodMonthAsc(companyId, to)) {
            if (s.getPeriodMonth().isBefore(from)) {
                continue;
            }
            ReportData d = read(s.getReportJson());
            out.add(TrendPoint.actual(s.getPeriodMonth(), d.profitLoss().revenue(),
                    d.profitLoss().operatingExpenses(), d.profitLoss().netProfit()));
        }
        if (forecast > 0) {
            out.addAll(TrendForecaster.forecast(out, forecast));
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
