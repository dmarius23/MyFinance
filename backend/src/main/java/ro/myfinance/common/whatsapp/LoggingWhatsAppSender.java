package ro.myfinance.common.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub transport: records the send in the log instead of delivering, so the whole WhatsApp pipeline
 * (history, outbox, per-module wiring, UI) works end-to-end without a provider. Active by default; set
 * {@code myfinance.whatsapp.provider=twilio} to switch to {@link TwilioWhatsAppSender}. PII-safe: logs
 * the recipient and body length, never the message text.
 */
@Component
@ConditionalOnProperty(name = "myfinance.whatsapp.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppSender.class);

    @Override
    public void send(Message message) {
        log.info("WhatsApp (stub) → {} : {} chars", message.toPhone(),
                message.body() == null ? 0 : message.body().length());
    }
}
