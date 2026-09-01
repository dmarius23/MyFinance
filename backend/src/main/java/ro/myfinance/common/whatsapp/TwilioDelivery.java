package ro.myfinance.common.whatsapp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Sends one WhatsApp message through Twilio's REST API ({@code /Messages.json}, form-POST, Basic auth) with
 * a supplied Account SID / auth token / sender number. Shared by the platform adapter
 * ({@link TwilioWhatsAppSender}) and the per-tenant sender ({@link TenantAwareWhatsAppSender}) so both hit
 * Twilio identically. Throws on a non-2xx response so the outbox relay retries.
 */
final class TwilioDelivery {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private TwilioDelivery() {
    }

    static void send(String accountSid, String authToken, String from, String defaultCountryCode,
                     WhatsAppSender.Message message) {
        if (accountSid == null || accountSid.isBlank() || authToken == null || authToken.isBlank()
                || from == null || from.isBlank()) {
            throw new IllegalStateException("Twilio WhatsApp is not configured (account-sid/auth-token/from)");
        }
        String to = PhoneNumbers.toE164(message.toPhone(), defaultCountryCode);
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("WhatsApp send has no recipient phone");
        }
        String body = "From=" + enc("whatsapp:" + PhoneNumbers.toE164(from, defaultCountryCode))
                + "&To=" + enc("whatsapp:" + to)
                + "&Body=" + enc(message.body() == null ? "" : message.body());
        String auth = Base64.getEncoder()
                .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Twilio WhatsApp request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "Twilio WhatsApp send rejected (HTTP " + resp.statusCode() + "): " + resp.body());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
