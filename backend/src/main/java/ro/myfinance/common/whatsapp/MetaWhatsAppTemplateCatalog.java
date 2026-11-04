package ro.myfinance.common.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;

/** Reads and manages the current tenant's message templates as Meta has them (WhatsApp Manager). */
@Component
public class MetaWhatsAppTemplateCatalog {

    private static final HttpClient DEFAULT_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** App locales a Meta template can be registered under; Meta's code for each comes from the sender. */
    private static final List<String> APP_LANGUAGES = List.of("ro_RO", "en_US");
    private static final int MAX_PAGES = 10;
    private static final Pattern META_TEMPLATE_NAME = Pattern.compile("[a-z][a-z0-9_]{1,511}");
    private static final Pattern META_ASSET_ID = Pattern.compile("[0-9]+");
    private static final Pattern META_VARIABLE = Pattern.compile("\\{\\{([1-9][0-9]*)\\}\\}");

    private final TenantWhatsAppProviderRepository providers;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final String graphBaseUrl;
    private final HttpClient http;

    @Autowired
    public MetaWhatsAppTemplateCatalog(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                                       ObjectMapper json,
                                       @Value("${myfinance.whatsapp.meta.graph-base-url:https://graph.facebook.com/v24.0}")
                                               String graphBaseUrl) {
        this(providers, cipher, json, graphBaseUrl, DEFAULT_HTTP);
    }

    MetaWhatsAppTemplateCatalog(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                                ObjectMapper json, String graphBaseUrl, HttpClient http) {
        this.providers = providers;
        this.cipher = cipher;
        this.json = json;
        this.graphBaseUrl = validateGraphBaseUrl(graphBaseUrl);
        this.http = http;
    }

    public List<MetaTemplate> list() {
        var tenant = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        TenantWhatsAppProvider provider = providers.findById(tenant)
                .filter(p -> p.getMode() == TenantWhatsAppProvider.Mode.META_CLOUD_API && p.isSendable())
                .orElseThrow(() -> new ConflictException("Connect Meta WhatsApp before loading its templates"));
        validateAssetId(provider.getMetaWabaId(), "WABA ID");
        String token = cipher.decrypt(provider.getMetaAccessTokenEnc());

        List<MetaTemplate> templates = new ArrayList<>();
        URI next = UriComponentsBuilder.fromUriString(graphBaseUrl + "/" + provider.getMetaWabaId()
                        + "/message_templates")
                .queryParam("fields", "name,language,status,category,components")
                .queryParam("limit", 100)
                .build().encode().toUri();
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            JsonNode body = get(next, token);
            body.path("data").forEach(t -> templates.add(toTemplate(t)));
            next = nextPage(body.path("paging").path("next").asText(null));
        }
        return templates;
    }

    public MetaTemplateMutation create(String name, String appLanguage, String body,
                                       List<String> exampleValues) {
        validateTemplateName(name);
        validateAssetId(provider().getMetaWabaId(), "WABA ID");
        validateBody(body, exampleValues);
        String language = MetaWhatsAppSender.metaLanguageCode(appLanguage);
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "BODY");
        component.put("text", body);
        if (exampleValues != null && !exampleValues.isEmpty()) {
            component.put("example", Map.of("body_text", List.of(exampleValues)));
        }
        JsonNode response = post("/" + provider().getMetaWabaId() + "/message_templates",
                Map.of("name", name, "language", language, "category", "UTILITY",
                        "components", List.of(component)), token());
        return new MetaTemplateMutation(name, language, response.path("id").asText(null),
                response.path("status").asText("PENDING"), "UTILITY");
    }

    /**
     * Meta deletes a template by name, including its translations. The caller must confirm this
     * destructive operation before invoking it.
     */
    public void delete(String name) {
        validateTemplateName(name);
        String wabaId = provider().getMetaWabaId();
        validateAssetId(wabaId, "WABA ID");
        // graphBaseUrl is validated as a fixed server-side origin in the constructor; name is restricted
        // to Meta's template-name grammar and is encoded as data in the query, never as a URL component.
        URI uri = UriComponentsBuilder.fromUriString(graphBaseUrl)
                .pathSegment(wabaId, "message_templates")
                .queryParam("name", name)
                .build().encode().toUri();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token())
                    .DELETE()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConflictException("Meta rejected template deletion (HTTP " + response.statusCode()
                        + metaError(response.body()) + ")");
            }
        } catch (ConflictException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Meta template deletion request was interrupted");
        } catch (Exception e) {
            throw new ConflictException("Could not delete the template from Meta: " + e.getMessage());
        }
    }

    private JsonNode get(URI uri, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConflictException("Meta rejected the template list (HTTP " + response.statusCode()
                        + metaError(response.body()) + ")");
            }
            return json.readTree(response.body());
        } catch (ConflictException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Meta template list request was interrupted");
        } catch (Exception e) {
            throw new ConflictException("Could not load templates from Meta: " + e.getMessage());
        }
    }

    private JsonNode post(String path, Map<String, Object> payload, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(graphBaseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ConflictException("Meta rejected template creation (HTTP " + response.statusCode()
                        + metaError(response.body()) + ")");
            }
            return json.readTree(response.body());
        } catch (ConflictException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Meta template creation request was interrupted");
        } catch (Exception e) {
            throw new ConflictException("Could not create the template in Meta: " + e.getMessage());
        }
    }

    private TenantWhatsAppProvider provider() {
        UUID tenant = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        return providers.findById(tenant)
                .filter(p -> p.getMode() == TenantWhatsAppProvider.Mode.META_CLOUD_API && p.isSendable())
                .orElseThrow(() -> new ConflictException("Connect Meta WhatsApp before managing templates"));
    }

    private static void validateTemplateName(String name) {
        if (name == null || !META_TEMPLATE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Meta WhatsApp template name");
        }
    }

    private static void validateBody(String body, List<String> exampleValues) {
        if (body == null || body.isBlank() || body.length() > 1024) {
            throw new IllegalArgumentException("Meta template body must contain 1–1024 characters");
        }
        java.util.Set<Integer> positions = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = META_VARIABLE.matcher(body);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor && body.substring(cursor, matcher.start()).contains("{{")) {
                throw new IllegalArgumentException("Meta template variables must use {{1}}, {{2}}, ...");
            }
            positions.add(Integer.parseInt(matcher.group(1)));
            cursor = matcher.end();
        }
        if (body.substring(cursor).contains("{{")) {
            throw new IllegalArgumentException("Meta template variables must use {{1}}, {{2}}, ...");
        }
        int expected = 1;
        for (Integer position : positions) {
            if (position != expected++) {
                throw new IllegalArgumentException("Meta template variables must be consecutive from {{1}}");
            }
        }
        int examples = exampleValues == null ? 0 : exampleValues.size();
        if (examples != positions.size()) {
            throw new IllegalArgumentException("Provide exactly one example for each Meta body variable");
        }
    }

    private static void validateAssetId(String id, String label) {
        if (id == null || !META_ASSET_ID.matcher(id).matches()) {
            throw new IllegalStateException("Invalid Meta " + label);
        }
    }

    private static String validateGraphBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean local = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            boolean production = "https".equalsIgnoreCase(uri.getScheme())
                    && "graph.facebook.com".equalsIgnoreCase(uri.getHost());
            if ((!local && !production) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Meta Graph URL must use an approved origin");
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Meta Graph URL", e);
        }
    }

    private String token() {
        return cipher.decrypt(provider().getMetaAccessTokenEnc());
    }

    /** Follows Meta's paging link only while it stays on the Graph host — the bearer token must not leak. */
    private URI nextPage(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        URI uri = URI.create(link);
        URI base = URI.create(graphBaseUrl);
        boolean sameHost = Objects.equals(uri.getScheme(), base.getScheme())
                && Objects.equals(uri.getHost(), base.getHost()) && uri.getPort() == base.getPort();
        return sameHost ? uri : null;
    }

    private MetaTemplate toTemplate(JsonNode t) {
        String language = t.path("language").asText("");
        String body = null;
        for (JsonNode component : t.path("components")) {
            if ("BODY".equals(component.path("type").asText())) {
                body = component.path("text").asText(null);
            }
        }
        String appLanguage = APP_LANGUAGES.stream()
                .filter(l -> MetaWhatsAppSender.metaLanguageCode(l).equals(language))
                .findFirst().orElse(null);
        return new MetaTemplate(t.path("name").asText(""), language, appLanguage,
                t.path("status").asText(""), t.path("category").asText(""), body);
    }

    private String metaError(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            if (error.isMissingNode()) {
                return "";
            }
            String detail = error.path("error_data").path("details").asText("");
            return ": #" + error.path("code").asText("?") + " " + error.path("message").asText("")
                    + (detail.isBlank() ? "" : " — " + detail);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * One template translation as Meta has it. {@code language} is Meta's code (e.g. {@code ro},
     * {@code en_US}); {@code appLanguage} is the app locale that sends it, or null if none does.
     */
    public record MetaTemplate(String name, String language, String appLanguage, String status,
                               String category, String body) {
    }

    public record MetaTemplateMutation(String name, String language, String id, String status,
                                       String category) {
    }
}
