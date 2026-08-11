package ro.myfinance.common.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase-1 stub transport: records the send in the log instead of delivering, so the whole WhatsApp
 * pipeline (history, outbox, per-module wiring, UI) works end-to-end before a real provider exists. The
 * future Twilio adapter will be selected via a config class (@ConditionalOnProperty) that also backs off
 * this stub. PII-safe: logs the recipient and body length, never the message text.
 */
@Component
public class LoggingWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppSender.class);

    @Override
    public void send(Message message) {
        log.info("WhatsApp (stub) → {} : {} chars", message.toPhone(),
                message.body() == null ? 0 : message.body().length());
    }
}
