package ro.myfinance.extraction.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.application.DocumentReminderService;
import ro.myfinance.extraction.application.DocumentReminderService.ReminderRow;
import ro.myfinance.extraction.application.DocumentReminderService.ReminderView;
import ro.myfinance.common.email.EmailStatus;

/**
 * Missing-document reminder emails for the bank-statements &amp; invoices hub: per-period last-sent
 * summary (for the Statements list), per-company history (notification log), and send/resend. Firm
 * staff only — representatives never reach these endpoints.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentReminderController {

    private final DocumentReminderService reminders;

    public DocumentReminderController(DocumentReminderService reminders) {
        this.reminders = reminders;
    }

    /** Per-company last-sent + count for the period (drives the Statements "last sent" column). */
    @GetMapping("/api/v1/document-reminders")
    public List<ReminderRowResponse> list(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reminders.listByPeriod(period).stream().map(ReminderRowResponse::from).toList();
    }

    /** Full send history for a company + period (notification log). */
    @GetMapping("/api/v1/companies/{companyId}/document-reminders")
    public List<ReminderViewResponse> history(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reminders.history(companyId, period).stream().map(ReminderViewResponse::from).toList();
    }

    /** Record + dispatch one reminder for a company. */
    @PostMapping("/api/v1/companies/{companyId}/document-reminders")
    public ReminderViewResponse send(@PathVariable UUID companyId, @RequestBody SendRequest req) {
        return ReminderViewResponse.from(
                reminders.send(companyId, req.period(), req.recipient(), req.body()));
    }

    public record SendRequest(LocalDate period, String recipient, String body) {
    }

    public record ReminderRowResponse(UUID companyId, Instant lastSentAt, int count) {
        static ReminderRowResponse from(ReminderRow r) {
            return new ReminderRowResponse(r.companyId(), r.lastSentAt(), r.count());
        }
    }

    public record ReminderViewResponse(UUID id, String recipient, EmailStatus status,
                                       Instant sentAt, String body) {
        static ReminderViewResponse from(ReminderView v) {
            return new ReminderViewResponse(v.id(), v.recipient(), v.status(), v.sentAt(), v.body());
        }
    }
}
