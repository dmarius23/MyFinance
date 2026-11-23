package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;

/** Fills TAX WhatsApp template variables from the month's verified declarations. */
@Component
class TaxWhatsAppTemplateValues implements WhatsAppTemplateValues {

    private final TaxPaymentService taxes;

    TaxWhatsAppTemplateValues(TaxPaymentService taxes) {
        this.taxes = taxes;
    }

    @Override
    public EmailKind kind() {
        return EmailKind.TAX;
    }

    @Override
    public Map<String, String> values(MessageTemplateKind sourceKind, UUID companyId, LocalDate period) {
        return taxes.templateValues(companyId, period);
    }
}
