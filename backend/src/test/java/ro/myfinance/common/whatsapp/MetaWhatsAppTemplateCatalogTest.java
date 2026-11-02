package ro.myfinance.common.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;

@ExtendWith(MockitoExtension.class)
class MetaWhatsAppTemplateCatalogTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock TenantWhatsAppProviderRepository providers;
    private HttpServer server;
    private TenantWhatsAppProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider = new TenantWhatsAppProvider(TENANT);
        provider.setMode(TenantWhatsAppProvider.Mode.META_CLOUD_API);
        provider.setMetaWabaId("waba-a");
        provider.setMetaPhoneNumberId("phone-a");
        provider.setMetaAccessTokenEnc(new SecretCipher(key()).encrypt("token-a"));
        when(providers.findById(TENANT)).thenReturn(Optional.of(provider));
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        server.stop(0);
    }

    @Test
    void listsTheTenantsWabaTemplatesAcrossPagesWithAppLanguage() {
        var auth = new AtomicReference<String>();
        String base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/waba-a/message_templates", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean second = exchange.getRequestURI().getQuery().contains("after=p2");
            respond(exchange, 200, second
                    ? """
                      {"data":[{"name":"document_reminder","language":"en","status":"APPROVED",
                                "category":"UTILITY","components":[]}]}
                      """
                    : """
                      {"data":[{"name":"document_reminder","language":"ro","status":"APPROVED",
                                "category":"UTILITY",
                                "components":[{"type":"BODY","text":"Buna {{1}}"}]}],
                       "paging":{"next":"%s/waba-a/message_templates?after=p2"}}
                      """.formatted(base));
        });
        server.start();

        var templates = catalog().list();

        assertThat(auth.get()).isEqualTo("Bearer token-a");
        assertThat(templates).extracting(MetaWhatsAppTemplateCatalog.MetaTemplate::language)
                .containsExactly("ro", "en");
        assertThat(templates.get(0).appLanguage()).isEqualTo("ro_RO");
        assertThat(templates.get(0).body()).isEqualTo("Buna {{1}}");
        // Plain "en" is not what an en_US send asks Meta for, so it maps to no app language.
        assertThat(templates.get(1).appLanguage()).isNull();
    }

    @Test
    void doesNotFollowPagingLinksOffTheGraphHost() {
        server.createContext("/waba-a/message_templates", exchange -> respond(exchange, 200, """
                {"data":[],"paging":{"next":"https://evil.example/steal"}}
                """));
        server.start();

        assertThat(catalog().list()).isEmpty();
    }

    @Test
    void refusesWhenTheTenantIsNotConnectedToMeta() {
        provider.setMode(TenantWhatsAppProvider.Mode.TWILIO);

        assertThatThrownBy(() -> catalog().list()).isInstanceOf(ConflictException.class);
    }

    private MetaWhatsAppTemplateCatalog catalog() {
        return new MetaWhatsAppTemplateCatalog(providers, new SecretCipher(key()), new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(), java.net.http.HttpClient.newHttpClient());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }
}
