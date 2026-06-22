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
import ro.myfinance.reports.application.ReportEmailService;
import ro.myfinance.reports.application.ReportEmailService.ReportEmailView;
import ro.myfinance.reports.application.ReportPdfGenerator;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.application.ReportService.ReportRow;
import ro.myfinance.reports.application.ReportService.TrendPoint;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.ReportEmail;

/**
 * MOD-06 Reports: monthly per-company report status, the computed report (JSON for charts), the branded
 * PDF download, the trend series, and email send/history. Firm staff only.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class ReportController {

    private final ReportService reports;
    private final ReportEmailService emails;
    private final ReportPdfGenerator pdf;

    public ReportController(ReportService reports, ReportEmailService emails, ReportPdfGenerator pdf) {
        this.reports = reports;
        this.emails = emails;
        this.pdf = pdf;
    }

    @GetMapping("/api/v1/reports")
    public List<ReportRowResponse> list(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reports.summary(period).stream().map(ReportRowResponse::from).toList();
    }

    /** The computed report (P&L + balance sheet + KPIs) — drives the on-screen charts. */
    @GetMapping("/api/v1/companies/{companyId}/report")
    public ReportData report(@PathVariable UUID companyId,
                             @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reports.report(companyId, period);
    }

    /** Revenue/expenses/net-profit trend across the last N months (default 12). */
    @GetMapping("/api/v1/companies/{companyId}/report/trend")
    public List<TrendPoint> trend(@PathVariable UUID companyId,
                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                                  @RequestParam(value = "months", defaultValue = "12") int months) {
        return reports.trend(companyId, period, months);
    }

    /** Download the branded monthly report PDF. */
    @GetMapping("/api/v1/companies/{companyId}/report/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        ReportData r = reports.report(companyId, period);
        byte[] bytes = pdf.generate(r);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("raport-financiar-" + period.withDayOfMonth(1) + ".pdf").build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(bytes);
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

    public record ReportEmailResponse(UUID id, String recipient, ReportEmail.Status status, Instant sentAt, String body) {
        static ReportEmailResponse from(ReportEmailView v) {
            return new ReportEmailResponse(v.id(), v.recipient(), v.status(), v.sentAt(), v.body());
        }
    }
}
