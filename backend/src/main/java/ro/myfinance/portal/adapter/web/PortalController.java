package ro.myfinance.portal.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.myfinance.portal.application.PortalService;
import ro.myfinance.portal.application.PortalService.CompanyInfo;
import ro.myfinance.portal.application.PortalService.DocView;
import ro.myfinance.portal.application.PortalService.MissingItem;
import ro.myfinance.portal.application.PortalService.PayrollFile;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.application.PeriodReportService.PeriodReportResult;
import ro.myfinance.reports.domain.ReportData;

/**
 * Representative PWA endpoints. A representative is scoped to a single company (from the JWT) and may
 * upload documents and view their own company's missing-document checklist, uploads, report and payroll.
 */
@RestController
@PreAuthorize("hasRole('REPRESENTATIVE')")
public class PortalController {

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping("/api/v1/portal/me")
    public CompanyInfo me() {
        return portal.me();
    }

    @PostMapping("/api/v1/portal/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocView upload(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "periodMonth", required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodMonth) {
        try {
            return portal.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes(), periodMonth);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    @GetMapping("/api/v1/portal/missing")
    public List<MissingItem> missing(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.missing(period);
    }

    @GetMapping("/api/v1/portal/documents")
    public List<DocView> myDocuments(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.myDocuments(period);
    }

    /**
     * The rep's own-company report for the period at the requested granularity (month by default). 204
     * when no data yet. Period coverage is returned in the {@code X-Report-*} headers so the PWA can flag
     * an incomplete quarter/half/year.
     */
    @GetMapping("/api/v1/portal/report")
    public ResponseEntity<ReportData> report(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "granularity", defaultValue = "MONTH") Granularity granularity) {
        PeriodReportResult res = portal.report(period, granularity);
        if (res == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header("X-Report-Complete", Boolean.toString(res.complete()))
                .header("X-Report-Months-Present", Integer.toString(res.monthsPresent()))
                .header("X-Report-Months-Expected", Integer.toString(res.monthsExpected()))
                .body(res.data());
    }

    /** Trend for the rep's company, optionally with {@code forecast} projected months (non-authoritative). */
    @GetMapping("/api/v1/portal/report/trend")
    public List<ro.myfinance.reports.application.ReportService.TrendPoint> trend(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "months", defaultValue = "12") int months,
            @RequestParam(value = "forecast", defaultValue = "0") int forecast) {
        return portal.trend(period, months, forecast);
    }

    @GetMapping("/api/v1/portal/report/pdf")
    public ResponseEntity<byte[]> reportPdf(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "granularity", defaultValue = "MONTH") Granularity granularity) {
        byte[] bytes = portal.reportPdf(period, granularity);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("raport-" + granularity.label(period) + ".pdf").build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(bytes);
    }

    @GetMapping("/api/v1/portal/payroll")
    public List<PayrollFile> payroll(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.payroll(period);
    }

    @GetMapping("/api/v1/portal/balance-sheet")
    public List<DocView> balanceSheet(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.balanceSheet(period);
    }

    @GetMapping("/api/v1/portal/company-documents")
    public List<DocView> companyDocuments(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.companyDocuments(period);
    }

    @GetMapping("/api/v1/portal/payments")
    public PortalService.PaymentView payments(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return portal.payments(period);
    }

    @GetMapping("/api/v1/portal/notifications")
    public List<ro.myfinance.notifications.application.NotificationService.NotificationView> notifications() {
        return portal.notifications();
    }

    @GetMapping("/api/v1/portal/notifications/unread-count")
    public UnreadCount unread() {
        return new UnreadCount(portal.unreadNotifications());
    }

    @PostMapping("/api/v1/portal/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        portal.markNotificationRead(id);
    }

    public record UnreadCount(long count) {
    }

    @GetMapping("/api/v1/portal/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var c = portal.download(id);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(c.document().getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.document().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(c.bytes());
    }
}
