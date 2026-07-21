package ro.myfinance.reports.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.reports.domain.ReportData;

/**
 * Compose, send and record the monthly report email to the representative. Each send attaches the branded
 * report PDF and a charts image. Recorded SENT/FAILED for the Reports list "last sent" + the log. The
 * resolve → send → record mechanics live in the shared {@link EmailDispatchService}; this service owns the
 * report-specific attachments, subject and the report-ready client notification.
 */
@Service
@Transactional
public class ReportEmailService {

    private final ReportService reports;
    private final EmailHistoryRepository history;
    private final ReportPdfGenerator pdf;
    private final EmailEnvelopeService envelopes;
    private final EmailDispatchService dispatch;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public ReportEmailService(ReportService reports, EmailHistoryRepository history, ReportPdfGenerator pdf,
                              EmailEnvelopeService envelopes, EmailDispatchService dispatch,
                              ro.myfinance.notifications.application.NotificationService notifications) {
        this.reports = reports;
        this.history = history;
        this.pdf = pdf;
        this.envelopes = envelopes;
        this.dispatch = dispatch;
        this.notifications = notifications;
    }

    /** One report email send (notification log + resend). */
    public record ReportEmailView(UUID id, String recipient, EmailStatus status, Instant sentAt, String body) {
        public static ReportEmailView from(EmailHistory e) {
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
        return history.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                        EmailKind.REPORT, companyId, period.withDayOfMonth(1))
                .stream().map(ReportEmailView::from).toList();
    }

    /** Record + dispatch the report email with the branded PDF + charts image attached. */
    public ReportEmailView send(UUID companyId, LocalDate period, String recipient, String body) {
        LocalDate month = period.withDayOfMonth(1);
        ReportData r = reports.report(companyId, month);
        List<EmailSender.Attachment> attachments = List.of(
                new EmailSender.Attachment("raport-" + ReportEmailBuilder.monthYear(month).replace(' ', '-') + ".pdf",
                        "application/pdf", pdf.generate(r)),
                new EmailSender.Attachment("grafice-" + ReportEmailBuilder.monthYear(month).replace(' ', '-') + ".png",
                        "image/png", ReportChartImage.png(r)));

        EmailHistory row = dispatch.dispatch(EmailKind.REPORT, companyId, period, recipient,
                ReportEmailBuilder.subject(month), body, attachments, null,
                () -> notifications.notifyCompanyReps(companyId, "REPORT_READY", "Raport disponibil",
                        "Raportul financiar pe luna " + ReportEmailBuilder.monthYear(month) + " este disponibil."));
        return ReportEmailView.from(row);
    }
}
