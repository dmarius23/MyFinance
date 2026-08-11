package ro.myfinance.common.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
        if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Twilio WhatsApp is not configured (account-sid/auth-token/from)");
        }
        String to = PhoneNumbers.toE164(message.toPhone(), defaultCountryCode);
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("WhatsApp send has no recipient phone");
        }
        String body = "From=" + enc("whatsapp:" + PhoneNumbers.toE164(from, defaultCountryCode))
                + "&To=" + enc("whatsapp:" + to)
                + "&Body=" + enc(message.body() == null ? "" : message.body());
        String auth = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Twilio WhatsApp request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Twilio WhatsApp send rejected (HTTP " + resp.statusCode() + "): " + resp.body());
        }
        log.info("WhatsApp (twilio) → {} delivered (HTTP {})", to, resp.statusCode());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
