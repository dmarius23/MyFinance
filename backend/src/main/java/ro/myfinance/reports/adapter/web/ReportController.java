package ro.myfinance.reports.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.application.PeriodReportService;
import ro.myfinance.reports.application.PeriodReportService.PeriodReportResult;
import ro.myfinance.reports.application.ReportEmailService;
import ro.myfinance.reports.application.ReportEmailService.ReportEmailView;
import ro.myfinance.reports.application.ReportPdfGenerator;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.application.ReportService.ReportRow;
import ro.myfinance.reports.application.ReportService.TrendPoint;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.common.email.EmailStatus;

/**
 * MOD-06 Reports: monthly per-company report status, the computed report (JSON for charts), the branded
 * PDF download, the trend series, and email send/history. Firm staff only.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class ReportController {

    private final ReportService reports;
    private final PeriodReportService periodReports;
    private final ReportEmailService emails;
    private final ReportPdfGenerator pdf;

    public ReportController(ReportService reports, PeriodReportService periodReports,
                            ReportEmailService emails, ReportPdfGenerator pdf) {
        this.reports = reports;
        this.periodReports = periodReports;
        this.emails = emails;
        this.pdf = pdf;
    }

    @GetMapping("/api/v1/reports")
    public List<ReportRowResponse> list(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reports.summary(period).stream().map(ReportRowResponse::from).toList();
    }

    /**
     * The computed report (P&L + balance sheet + KPIs) for the calendar period of {@code granularity}
     * enclosing {@code period} — drives the on-screen charts. Defaults to the raw MONTH.
     * Period coverage is returned in the {@code X-Report-*} headers so the UI can flag an incomplete
     * quarter/half/year ("2 of 3 months") without changing the report body shape.
     */
    @GetMapping("/api/v1/companies/{companyId}/report")
    public ResponseEntity<ReportData> report(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "granularity", defaultValue = "MONTH") Granularity granularity) {
        PeriodReportResult res = periodReports.report(companyId, granularity, period);
        return ResponseEntity.ok()
                .header("X-Report-Complete", Boolean.toString(res.complete()))
                .header("X-Report-Months-Present", Integer.toString(res.monthsPresent()))
                .header("X-Report-Months-Expected", Integer.toString(res.monthsExpected()))
                .body(res.data());
    }

    /**
     * Revenue/expenses/net-profit trend across the last N months (default 12), optionally followed by
     * {@code forecast} projected months (default 0). Projected points are flagged and non-authoritative.
     */
    @GetMapping("/api/v1/companies/{companyId}/report/trend")
    public List<TrendPoint> trend(@PathVariable UUID companyId,
                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                                  @RequestParam(value = "months", defaultValue = "12") int months,
                                  @RequestParam(value = "forecast", defaultValue = "0") int forecast) {
        return reports.trend(companyId, period, months, forecast);
    }

    /** Download the branded report PDF for the requested period (MONTH by default). */
    @GetMapping("/api/v1/companies/{companyId}/report/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "granularity", defaultValue = "MONTH") Granularity granularity) {
        ReportData r = periodReports.report(companyId, granularity, period).data();
        byte[] bytes = pdf.generate(r);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("raport-financiar-" + periodLabel(granularity, period) + ".pdf").build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(bytes);
    }

    /** A filename-friendly period label, e.g. {@code 2026-03} (month), {@code 2026-Q2}, {@code 2026-H1}, {@code 2026}. */
    private static String periodLabel(Granularity granularity, LocalDate period) {
        LocalDate start = granularity.periodStart(period);
        return switch (granularity) {
            case MONTH -> start.toString().substring(0, 7);              // yyyy-MM
            case QUARTER -> start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
            case HALF -> start.getYear() + "-H" + ((start.getMonthValue() - 1) / 6 + 1);
            case YEAR -> Integer.toString(start.getYear());
        };
    }

    @GetMapping("/api/v1/companies/{companyId}/report-emails")
    public List<ReportEmailResponse> history(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return emails.history(companyId, period).stream().map(ReportEmailResponse::from).toList();
    }

    @GetMapping("/api/v1/companies/{companyId}/report/email-body")
    public BodyResponse body(@PathVariable UUID companyId,
                             @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return new BodyResponse(emails.composeBody(companyId, period));
    }

    @PostMapping("/api/v1/companies/{companyId}/report-emails")
    public ReportEmailResponse send(@PathVariable UUID companyId, @RequestBody SendRequest req) {
        return ReportEmailResponse.from(emails.send(companyId, req.period(), req.recipient(), req.body()));
    }

    public record SendRequest(LocalDate period, String recipient, String body) {
    }

    public record BodyResponse(String body) {
    }

    public record ReportRowResponse(UUID companyId, Instant uploadedAt, int version, boolean balanced,
                                    Instant lastSentAt, int sentCount) {
        static ReportRowResponse from(ReportRow r) {
            return new ReportRowResponse(r.companyId(), r.uploadedAt(), r.version(), r.balanced(),
                    r.lastSentAt(), r.sentCount());
        }
    }

    public record ReportEmailResponse(UUID id, String recipient, EmailStatus status, Instant sentAt, String body) {
        static ReportEmailResponse from(ReportEmailView v) {
            return new ReportEmailResponse(v.id(), v.recipient(), v.status(), v.sentAt(), v.body());
        }
    }
}
