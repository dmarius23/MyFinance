package ro.myfinance.common.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

@ExtendWith(MockitoExtension.class)
class MetaWhatsAppSenderTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock TenantWhatsAppProviderRepository providers;
    @Mock WhatsAppMediaSource media;
    private HttpServer server;
    private TenantWhatsAppProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider = new TenantWhatsAppProvider(TENANT);
        provider.setMode(TenantWhatsAppProvider.Mode.META_CLOUD_API);
        provider.setMetaBusinessPortfolioId("business");
        provider.setMetaWabaId("waba");
        provider.setMetaPhoneNumberId("phone");
        provider.setMetaAccessTokenEnc(new SecretCipher(key()).encrypt("token"));
        when(providers.findById(TENANT)).thenReturn(Optional.of(provider));
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTemplateAndReturnsMetaMessageId() throws Exception {
        server.createContext("/phone/messages", exchange -> {
            respond(exchange, 200, """
                    {"messages":[{"id":"wamid.test"}]}
                    """);
        });
        server.start();

        WhatsAppSender.DeliveryReceipt receipt = sender().send(message());

        assertThat(receipt.providerMessageId()).isEqualTo("wamid.test");
    }

    @Test
    void sendsMetaLanguageCodeInsteadOfLocale() throws Exception {
        var sentBody = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/phone/messages", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"messages":[{"id":"wamid.test"}]}
                    """);
        });
        server.start();

        sender().send(message());

        assertThat(new ObjectMapper().readTree(sentBody.get())
                .path("template").path("language").path("code").asText()).isEqualTo("ro");
        assertThat(MetaWhatsAppSender.metaLanguageCode("en_US")).isEqualTo("en_US");
    }

    @Test
    void classifiesClientErrorsAsPermanent() throws Exception {
        server.createContext("/phone/messages", exchange -> {
            respond(exchange, 400, """
                    {"error":{"message":"(#132000) Number of parameters does not match","code":132000,
                     "error_data":{"details":"body: expected 0, received 1"}}}
                    """);
        });
        server.start();

        assertThatThrownBy(() -> sender().send(message()))
                .isInstanceOfSatisfying(WhatsAppProviderException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("HTTP 400", "#132000", "expected 0, received 1");
                });
    }

    @Test
    void classifiesServerErrorsAsRetryable() throws Exception {
        server.createContext("/phone/messages", exchange -> {
            respond(exchange, 503, "{}");
        });
        server.start();

        assertThatThrownBy(() -> sender().send(message()))
                .isInstanceOfSatisfying(WhatsAppProviderException.class,
                        e -> assertThat(e.isRetryable()).isTrue());
    }

    private MetaWhatsAppSender sender() {
        return new MetaWhatsAppSender(providers, new SecretCipher(key()), new ObjectMapper(), media,
                "http://localhost:" + server.getAddress().getPort(),
                java.net.http.HttpClient.newHttpClient());
    }

    private WhatsAppSender.Message message() {
        return new WhatsAppSender.Message("MyFinance", "+40700000000", "body", "tax_notice", "ro_RO",
                Map.of("companyName", "Acme"), UUID.randomUUID(), List.of());
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
