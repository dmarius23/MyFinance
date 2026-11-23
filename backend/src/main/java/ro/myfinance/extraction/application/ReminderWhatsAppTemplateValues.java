package ro.myfinance.extraction.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;

/** Fills DOCUMENT_REMINDER WhatsApp template variables from the same values the reminder email renders. */
@Component
class ReminderWhatsAppTemplateValues implements WhatsAppTemplateValues {

    private final ReminderBodyService reminders;

    ReminderWhatsAppTemplateValues(ReminderBodyService reminders) {
        this.reminders = reminders;
    }

    @Override
    public EmailKind kind() {
        return EmailKind.DOCUMENT_REMINDER;
    }

    @Override
    public Map<String, String> values(MessageTemplateKind sourceKind, UUID companyId, LocalDate period) {
        return reminders.templateValues(companyId, period);
    }
}
