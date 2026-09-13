package ro.myfinance.common.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real WhatsApp transport via Twilio's REST API (no SDK dependency — a direct form-POST to
 * {@code /Messages.json} with Basic auth). Active when {@code myfinance.whatsapp.provider=twilio}.
 * Throws on a non-2xx response so the outbox relay retries and, on exhaustion, marks the history FAILED.
 *
 * <p>Config (env → property):
 * {@code myfinance.whatsapp.twilio.account-sid}, {@code …auth-token}, {@code …from} (the Twilio WhatsApp
 * sender, e.g. the sandbox {@code +14155238886}), and {@code myfinance.whatsapp.default-country-code}
 * (default {@code +40}) used to normalise local numbers to E.164.
 *
 * <p>Sandbox note: the recipient must first opt in by sending {@code join <code>} to the sandbox number;
 * production requires an approved sender + templates for business-initiated messages.
 */
@Component
@ConditionalOnProperty(name = "myfinance.whatsapp.provider", havingValue = "twilio")
public class TwilioWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppSender.class);

    private final String accountSid;
    private final String authToken;
    private final String from;
    private final String defaultCountryCode;

    public TwilioWhatsAppSender(
            @Value("${myfinance.whatsapp.twilio.account-sid:}") String accountSid,
            @Value("${myfinance.whatsapp.twilio.auth-token:}") String authToken,
            @Value("${myfinance.whatsapp.twilio.from:}") String from,
            @Value("${myfinance.whatsapp.default-country-code:+40}") String defaultCountryCode) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.from = from;
        this.defaultCountryCode = defaultCountryCode;
    }

    @Override
    public void send(Message message) {
        TwilioDelivery.send(accountSid, authToken, from, defaultCountryCode, message);
        log.info("WhatsApp (twilio) delivered");
    }
}
