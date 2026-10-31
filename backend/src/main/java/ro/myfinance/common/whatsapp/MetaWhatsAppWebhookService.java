package ro.myfinance.common.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/** Verifies and applies Meta delivery-status callbacks without trusting tenant-supplied identifiers. */
@Service
public class MetaWhatsAppWebhookService {

    private final JdbcTemplate admin;
    private final WhatsAppMessageRepository messages;
    private final ObjectMapper json;
    private final String appSecret;
    private final String verifyToken;

    public MetaWhatsAppWebhookService(@Qualifier("adminJdbcTemplate") JdbcTemplate admin,
                                      WhatsAppMessageRepository messages,
                                      ObjectMapper json,
                                      @Value("${myfinance.whatsapp.meta.app-secret:}") String appSecret,
                                      @Value("${myfinance.whatsapp.meta.webhook-verify-token:}") String verifyToken) {
        this.admin = admin;
        this.messages = messages;
        this.json = json;
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
    }

    public boolean verifySubscription(String mode, String suppliedToken, String challenge) {
        return "subscribe".equals(mode) && verifyToken != null && !verifyToken.isBlank()
                && suppliedToken != null && MessageDigest.isEqual(
                        verifyToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))
                && challenge != null;
    }

    public int handle(String signature, String rawBody) {
        verifySignature(signature, rawBody);
        try {
            JsonNode root = json.readTree(rawBody);
            int processed = 0;
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
                    if (phoneNumberId == null || phoneNumberId.isBlank()) {
                        continue;
                    }
                    UUID tenantId = findTenant(phoneNumberId);
                    if (tenantId == null) {
                        continue;
                    }
                    TenantContext.set(new TenantContext.Identity(tenantId, null, Role.TENANT_ADMIN, null));
                    try {
                        for (JsonNode status : value.path("statuses")) {
                            if (applyStatus(status)) {
                                processed++;
                            }
                        }
                    } finally {
                        TenantContext.clear();
                    }
                }
            }
            return processed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Meta WhatsApp webhook payload", e);
        }
    }

    private UUID findTenant(String phoneNumberId) {
        return admin.query("""
                select tenant_id from tenant_whatsapp_provider
                where mode = 'META_CLOUD_API' and meta_phone_number_id = ?
                """, rs -> rs.next() ? rs.getObject("tenant_id", UUID.class) : null, phoneNumberId);
    }

    private boolean applyStatus(JsonNode status) {
        String providerMessageId = status.path("id").asText(null);
        String providerStatus = status.path("status").asText(null);
        if (providerMessageId == null || providerMessageId.isBlank()
                || providerStatus == null || providerStatus.isBlank()) {
            return false;
        }
        WhatsAppMessage row = messages.findByProviderMessageId(providerMessageId).orElse(null);
        if (row == null) {
            return false;
        }
        long timestamp = status.path("timestamp").asLong(0);
        row.applyProviderStatus(providerStatus, timestamp > 0 ? Instant.ofEpochSecond(timestamp) : Instant.now());
        messages.save(row);
        return true;
    }

    private void verifySignature(String supplied, String body) {
        if (appSecret == null || appSecret.isBlank() || supplied == null
                || !supplied.startsWith("sha256=")) {
            throw new IllegalArgumentException("Invalid Meta webhook signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] actual = java.util.HexFormat.of().parseHex(supplied.substring("sha256=".length()));
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("Invalid Meta webhook signature");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify Meta webhook signature", e);
        }
    }
}
