package ro.myfinance.common.whatsapp;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;

/**
 * Port: the placeholder values a module renders its client message from (company name, month, totals…),
 * reused server-side to fill the {@code {{n}}} variables of the Meta template mapped to that message.
 * Each module implements it from the same computation its email uses, so WhatsApp and email state the same
 * facts — and the browser never supplies a value that reaches the client.
 */
public interface WhatsAppTemplateValues {

    /** The module this source fills. */
    EmailKind kind();

    /**
     * Placeholder name → value for one company/period, keyed by the names declared on
     * {@link MessageTemplateKind}. Absent or blank values are reported to staff as missing.
     */
    Map<String, String> values(MessageTemplateKind sourceKind, UUID companyId, LocalDate period);
}
