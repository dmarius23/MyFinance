package ro.myfinance.reports.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.reports.adapter.persistence.ReportEmailRepository;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.ReportEmail;
import ro.myfinance.taxpayments.application.EmailSender;

/**
 * Compose, send and record the monthly report email to the representative. Each send attaches the branded
 * report PDF and a charts image. Recorded SENT/FAILED for the Reports list "last sent" + the log.
 */
@Service
@Transactional
public class ReportEmailService {

    private static final Logger log = LoggerFactory.getLogger(ReportEmailService.class);

    private final ReportService reports;
    private final ReportEmailRepository emails;
    private final ReportPdfGenerator pdf;
    private final EmailEnvelopeService envelopes;
    private final EmailSender sender;

    public ReportEmailService(ReportService reports, ReportEmailRepository emails, ReportPdfGenerator pdf,
                              EmailEnvelopeService envelopes, EmailSender sender) {
        this.reports = reports;
        this.emails = emails;
        this.pdf = pdf;
        this.envelopes = envelopes;
        this.sender = sender;
    }

    /** One report email send (notification log + resend). */
    public record ReportEmailView(UUID id, String recipient, ReportEmail.Status status, Instant sentAt, String body) {
        public static ReportEmailView from(ReportEmail e) {
            return new ReportEmailView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(), e.getBody());
        }
    }

    /** Default editable body for a company/period (signed with the logged-in user's name). */
    @Transactional(readOnly = true)
    public String composeBody(UUID companyId, LocalDate period) {
        ReportData r = reports.report(companyId, period);
        return ReportEmailBuilder.body(period.withDayOfMonth(1), r, envelopes.currentUserName());
    }

    @Transactional(readOnly = true)
    public List<ReportEmailView> history(UUID companyId, LocalDate period) {
        return emails.findByCompanyIdAndPeriodMonthOrderBySentAtDesc(companyId, period.withDayOfMonth(1))
                .stream().map(ReportEmailView::from).toList();
    }

    /** Record + dispatch the report email with the branded PDF + charts image attached. */
    public ReportEmailView send(UUID companyId, LocalDate period, String recipient, String body) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);

        ReportData r = reports.report(companyId, month);
        var env = envelopes.resolve(companyId, recipient);
        String to = env.recipient();
        List<EmailSender.Attachment> attachments = List.of(
                new EmailSender.Attachment("raport-" + ReportEmailBuilder.monthYear(month).replace(' ', '-') + ".pdf",
                        "application/pdf", pdf.generate(r)),
                new EmailSender.Attachment("grafice-" + ReportEmailBuilder.monthYear(month).replace(' ', '-') + ".png",
                        "image/png", ReportChartImage.png(r)));

        ReportEmail.Status status = ReportEmail.Status.SENT;
        String error = null;
        try {
            sender.send(new EmailSender.Message(env.fromName(), env.fromEmail(), to,
                    ReportEmailBuilder.subject(month), body, attachments));
        } catch (RuntimeException e) {
            status = ReportEmail.Status.FAILED;
            error = e.getMessage();
            log.warn("Report email send failed for company {} period {}", companyId, month, e);
        }
        return ReportEmailView.from(emails.save(new ReportEmail(
                tenantId, companyId, month, to, body, status, error, userId)));
    }
}
