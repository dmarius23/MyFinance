package ro.myfinance.reports.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;

/** Fills REPORT WhatsApp template variables from the same figures the report email renders. */
@Component
class ReportWhatsAppTemplateValues implements WhatsAppTemplateValues {

    private final ReportEmailService reports;

    ReportWhatsAppTemplateValues(ReportEmailService reports) {
        this.reports = reports;
    }

    @Override
    public EmailKind kind() {
        return EmailKind.REPORT;
    }

    @Override
    public Map<String, String> values(MessageTemplateKind sourceKind, UUID companyId, LocalDate period) {
        return reports.templateValues(companyId, period);
    }
}
