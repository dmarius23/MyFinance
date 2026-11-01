package ro.myfinance.common.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.myfinance.common.crypto.SecretCipher;

/** Sends approved WhatsApp templates through Meta's Graph API for the current tenant. */
@Component
public class MetaWhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppSender.class);
    private static final HttpClient DEFAULT_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Meta template languages keep the region only for these locales; all others are the bare language. */
    private static final Set<String> REGIONAL_META_LANGUAGES = Set.of(
            "en_GB", "en_US", "es_AR", "es_ES", "es_MX", "pt_BR", "pt_PT", "zh_CN", "zh_HK", "zh_TW");

    private final TenantWhatsAppProviderRepository providers;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final WhatsAppMediaSource mediaSource;
    private final String graphBaseUrl;
    private final HttpClient http;

    @Autowired
    public MetaWhatsAppSender(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                              ObjectMapper json, WhatsAppMediaSource mediaSource,
                              @Value("${myfinance.whatsapp.meta.graph-base-url:https://graph.facebook.com/v24.0}")
                                      String graphBaseUrl) {
        this(providers, cipher, json, mediaSource, graphBaseUrl, DEFAULT_HTTP);
    }

    MetaWhatsAppSender(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                       ObjectMapper json, WhatsAppMediaSource mediaSource, String graphBaseUrl,
                       HttpClient http) {
        this.providers = providers;
        this.cipher = cipher;
        this.json = json;
        this.mediaSource = mediaSource;
        this.graphBaseUrl = graphBaseUrl;
        this.http = http;
    }

    static String metaLanguageCode(String locale) {
        int separator = locale.indexOf('_');
        return separator < 0 || REGIONAL_META_LANGUAGES.contains(locale) ? locale : locale.substring(0, separator);
    }

    public WhatsAppSender.DeliveryReceipt send(WhatsAppSender.Message message) {
        var tenant = ro.myfinance.common.security.TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        TenantWhatsAppProvider provider = providers.findById(tenant)
                .orElseThrow(() -> new IllegalStateException("Meta WhatsApp is not configured"));
        if (provider.getMode() != TenantWhatsAppProvider.Mode.META_CLOUD_API
                || !provider.isSendable()) {
            throw new IllegalStateException("Meta WhatsApp is not configured for this tenant");
        }
        if (message.templateName() == null || message.templateName().isBlank()
                || message.templateLanguage() == null || message.templateLanguage().isBlank()) {
            throw new IllegalStateException("Meta WhatsApp message has no approved template");
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", message.templateName());
        template.put("language", Map.of("code", metaLanguageCode(message.templateLanguage())));
        if (!message.templateVariables().isEmpty()) {
            var parameters = new ArrayList<Map<String, Object>>();
            message.templateVariables().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> parameters.add(Map.of("type", "text", "text", entry.getValue())));
            template.put("components", java.util.List.of(Map.of("type", "body", "parameters", parameters)));
        }

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", message.toPhone(),
                "type", "template",
                "template", template);
        try {
            String body = json.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(graphBaseUrl + "/" + provider.getMetaPhoneNumberId() + "/messages"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + cipher.decrypt(provider.getMetaAccessTokenEnc()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw providerFailure("Meta WhatsApp send rejected", response);
            }
            String providerMessageId = json.readTree(response.body()).path("messages").path(0)
                    .path("id").asText(null);
            if (providerMessageId == null || providerMessageId.isBlank()) {
                throw new WhatsAppProviderException("Meta WhatsApp response did not include a message id", false);
            }
            for (WhatsAppSender.Attachment attachment : message.attachments()) {
                sendMedia(message, provider, attachment);
            }
            log.info("[whatsapp:meta] delivered");
            return new WhatsAppSender.DeliveryReceipt(providerMessageId);
        } catch (WhatsAppProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WhatsAppProviderException("Meta WhatsApp request failed: " + e.getMessage(), e, true);
        }
    }

    private void sendMedia(WhatsAppSender.Message message, TenantWhatsAppProvider provider,
                           WhatsAppSender.Attachment attachment) throws Exception {
        WhatsAppMediaSource.Media media = mediaSource.load(message.companyId(), attachment.documentId());
        String mediaId = upload(provider, media);
        boolean image = media.contentType().startsWith("image/");
        Map<String, Object> mediaBody = image
                ? Map.of("id", mediaId)
                : Map.of("id", mediaId, "filename", media.filename());
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", message.toPhone(),
                "type", image ? "image" : "document",
                image ? "image" : "document", mediaBody);
        postJson(provider.getMetaPhoneNumberId() + "/messages", cipher.decrypt(provider.getMetaAccessTokenEnc()),
                payload);
    }

    private String upload(TenantWhatsAppProvider provider, WhatsAppMediaSource.Media media) throws Exception {
        String boundary = "----myfinance-" + java.util.UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart(out, boundary, "messaging_product", null, "whatsapp".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writePart(out, boundary, "file", media.filename(), media.bytes(), media.contentType());
        out.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(graphBaseUrl + "/" + provider.getMetaPhoneNumberId() + "/media"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + cipher.decrypt(provider.getMetaAccessTokenEnc()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw providerFailure("Meta WhatsApp media upload rejected", response);
        }
        String mediaId = json.readTree(response.body()).path("id").asText(null);
        if (mediaId == null || mediaId.isBlank()) {
            throw new WhatsAppProviderException("Meta WhatsApp media response did not include an id", false);
        }
        return mediaId;
    }

    private com.fasterxml.jackson.databind.JsonNode postJson(String path, String token,
                                                              Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(graphBaseUrl + "/" + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw providerFailure("Meta WhatsApp media send rejected", response);
        }
        return json.readTree(response.body());
    }

    private WhatsAppProviderException providerFailure(String message, HttpResponse<String> response) {
        int status = response.statusCode();
        boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
        return new WhatsAppProviderException(message + " (HTTP " + status + metaErrorDetail(response.body()) + ")",
                retryable);
    }

    /** Meta's own reason, e.g. ": #132000 Number of parameters does not match ..." — never echoes the request. */
    private String metaErrorDetail(String body) {
        try {
            var error = json.readTree(body).path("error");
            if (error.isMissingNode()) {
                return "";
            }
            String detail = error.path("error_data").path("details").asText("");
            String text = "#" + error.path("code").asText("?") + " " + error.path("message").asText("")
                    + (detail.isBlank() ? "" : " — " + detail);
            return ": " + (text.length() > 400 ? text.substring(0, 400) + "…" : text);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name,
                                  String filename, byte[] bytes) throws Exception {
        writePart(out, boundary, name, filename, bytes, "application/octet-stream");
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name,
                                  String filename, byte[] bytes, String contentType) throws Exception {
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename.replace("\"", "") + "\"");
        out.write(("--" + boundary + "\r\n" + disposition + "\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
