package ro.myfinance.payroll.adapter.web;

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
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.payroll.application.PayrollService.PayrollDoc;
import ro.myfinance.payroll.application.PayrollService.PayrollEmailView;
import ro.myfinance.payroll.application.PayrollService.PayrollRow;
import ro.myfinance.payroll.domain.PayrollEmail;

/**
 * MOD-08 Payroll: monthly per-company payroll status, email preview, and email send/history. Salary
 * data is sensitive — firm staff only; representatives never reach these endpoints.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class PayrollController {

    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    /** Per-company payroll rows for the period (docs uploaded + last-sent). */
    @GetMapping("/api/v1/payroll")
    public List<PayrollRowResponse> list(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return payroll.summary(period).stream().map(PayrollRowResponse::from).toList();
    }

    /** Default editable email body for a company/period. */
    @GetMapping("/api/v1/companies/{companyId}/payroll/email-body")
    public BodyResponse body(@PathVariable UUID companyId,
                             @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return new BodyResponse(payroll.composeBody(companyId, period));
    }

    /** Full send history for a company + period (notification log). */
    @GetMapping("/api/v1/companies/{companyId}/payroll/emails")
    public List<PayrollEmailResponse> history(
            @PathVariable UUID companyId,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return payroll.history(companyId, period).stream().map(PayrollEmailResponse::from).toList();
    }

    /** Record + dispatch one payroll email (attaches the company's payroll documents). */
    @PostMapping("/api/v1/companies/{companyId}/payroll/emails")
    public PayrollEmailResponse send(@PathVariable UUID companyId, @RequestBody SendRequest req) {
        return PayrollEmailResponse.from(
                payroll.send(companyId, req.period(), req.recipient(), req.body(), req.documentIds()));
    }

    /** {@code documentIds} = the payroll documents to attach; null attaches all for the period. */
    public record SendRequest(LocalDate period, String recipient, String body, List<UUID> documentIds) {
    }

    public record BodyResponse(String body) {
    }

    public record PayrollDocResponse(UUID id, String filename) {
        static PayrollDocResponse from(PayrollDoc d) {
            return new PayrollDocResponse(d.id(), d.filename());
        }
    }

    public record PayrollRowResponse(UUID companyId, List<PayrollDocResponse> documents,
                                     Instant lastSentAt, int sentCount) {
        static PayrollRowResponse from(PayrollRow r) {
            return new PayrollRowResponse(r.companyId(),
                    r.documents().stream().map(PayrollDocResponse::from).toList(),
                    r.lastSentAt(), r.sentCount());
        }
    }

    public record PayrollEmailResponse(UUID id, String recipient, PayrollEmail.Status status,
                                       Instant sentAt, List<UUID> documentIds, String body) {
        static PayrollEmailResponse from(PayrollEmailView v) {
            return new PayrollEmailResponse(v.id(), v.recipient(), v.status(), v.sentAt(),
                    v.documentIds(), v.body());
        }
    }
}
