package ro.myfinance.settings.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.MisconfiguredException;

/** Meta Graph API calls used only while completing Embedded Signup. */
@Component
public class MetaWhatsAppOnboardingClient {

    private final RestClient http;
    private final ObjectMapper json;
    private final String appId;
    private final String appSecret;
    private final String graphBaseUrl;

    public MetaWhatsAppOnboardingClient(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value("${myfinance.whatsapp.meta.app-id:}") String appId,
            @Value("${myfinance.whatsapp.meta.app-secret:}") String appSecret,
            @Value("${myfinance.whatsapp.meta.graph-base-url:https://graph.facebook.com/v24.0}")
            String graphBaseUrl) {
        this.http = builder.build();
        this.json = json;
        this.appId = appId;
        this.appSecret = appSecret;
        this.graphBaseUrl = graphBaseUrl;
    }

    public String exchangeCode(String code, String redirectUri) {
        ensureConfigured();
        String response;
        try {
            response = http.get()
                    .uri(UriComponentsBuilder.fromUriString(graphBaseUrl + "/oauth/access_token")
                            .queryParam("client_id", appId)
                            .queryParam("client_secret", appSecret)
                            .queryParam("code", code)
                            .queryParam("redirect_uri", redirectUri)
                            .build().encode().toUriString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw rejected("token exchange", e);
        }
        return required(response, "access_token", "Meta did not return an access token");
    }

    /**
     * WABA IDs the user granted to this token during Embedded Signup. The redirect flow does not return
     * asset IDs, so they are read from the token's {@code whatsapp_business_management} granular scope.
     */
    public List<String> grantedWabaIds(String token) {
        ensureConfigured();
        JsonNode node;
        try {
            String response = http.get()
                    .uri(UriComponentsBuilder.fromUriString(graphBaseUrl + "/debug_token")
                            .queryParam("input_token", token)
                            .queryParam("access_token", appId + "|" + appSecret)
                            .build().encode().toUriString())
                    .retrieve()
                    .body(String.class);
            node = json.readTree(response);
        } catch (RestClientResponseException e) {
            throw rejected("token inspection", e);
        } catch (Exception e) {
            throw new MisconfiguredException("Meta returned an unreadable token inspection response");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode scope : node.path("data").path("granular_scopes")) {
            if ("whatsapp_business_management".equals(scope.path("scope").asText())) {
                scope.path("target_ids").forEach(id -> ids.add(id.asText()));
            }
        }
        return ids;
    }

    public List<String> phoneNumberIds(String token, String wabaId) {
        JsonNode node = get(token, "/" + wabaId + "/phone_numbers?fields=id");
        List<String> ids = new ArrayList<>();
        node.path("data").forEach(phone -> ids.add(phone.path("id").asText()));
        return ids;
    }

    public void validatePhoneNumber(String token, String phoneNumberId) {
        JsonNode node = get(token, "/" + phoneNumberId
                + "?fields=id,display_phone_number,verified_name,platform_type");
        if (!phoneNumberId.equals(node.path("id").asText())) {
            throw new ConflictException("Meta returned a different WhatsApp phone-number ID");
        }
    }

    /** Validates the WABA and returns its owning business portfolio ID, or {@code null} if Meta omits it. */
    public String validateWaba(String token, String wabaId) {
        JsonNode node = get(token, "/" + wabaId + "?fields=id,owner_business_info");
        if (!wabaId.equals(node.path("id").asText())) {
            throw new ConflictException("Meta returned a different WhatsApp Business Account ID");
        }
        String owner = node.path("owner_business_info").path("id").asText(null);
        return owner == null || owner.isBlank() ? null : owner;
    }

    /**
     * Subscribes this Meta app to the WABA so Meta starts delivering message-status callbacks.
     * WABA subscription is separate from validating the WABA and phone-number assets.
     */
    public void subscribeToWaba(String token, String wabaId) {
        try {
            http.post()
                    .uri(graphBaseUrl + "/" + wabaId + "/subscribed_apps")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw rejected("webhook subscription", e);
        }
    }

    private JsonNode get(String token, String path) {
        try {
            String response = http.get().uri(graphBaseUrl + path)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(String.class);
            return json.readTree(response);
        } catch (RestClientResponseException e) {
            throw rejected("asset validation", e);
        } catch (Exception e) {
            throw new MisconfiguredException("Meta returned an unreadable asset response");
        }
    }

    private void ensureConfigured() {
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new MisconfiguredException(
                    "Meta WhatsApp OAuth is not configured — set META_WHATSAPP_APP_ID and "
                            + "META_WHATSAPP_APP_SECRET");
        }
    }

    private String required(String response, String field, String message) {
        try {
            String value = json.readTree(response).path(field).asText(null);
            if (value == null || value.isBlank()) {
                throw new ConflictException(message);
            }
            return value;
        } catch (ConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new MisconfiguredException("Meta returned an unreadable token response");
        }
    }

    private RuntimeException rejected(String stage, RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        if (detail == null || detail.isBlank()) {
            detail = e.getStatusText();
        }
        detail = detail.replaceAll("\\s+", " ");
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        return new ConflictException("Meta rejected OAuth " + stage + ": " + detail);
    }
}
