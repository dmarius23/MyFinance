package ro.myfinance.payroll.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;

/** Fills PAYROLL WhatsApp template variables from the same values the payroll email renders. */
@Component
class PayrollWhatsAppTemplateValues implements WhatsAppTemplateValues {

    private final PayrollService payroll;

    PayrollWhatsAppTemplateValues(PayrollService payroll) {
        this.payroll = payroll;
    }

    @Override
    public EmailKind kind() {
        return EmailKind.PAYROLL;
    }

    @Override
    public Map<String, String> values(MessageTemplateKind sourceKind, UUID companyId, LocalDate period) {
        return payroll.templateValues(companyId, period);
    }
}
