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

    @Test
    void sendsParametersInDeclaredOrderAndFlattensMultiLineValues() throws Exception {
        var sentBody = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/phone/messages", exchange -> {
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"messages":[{"id":"wamid.test"}]}
                    """);
        });
        server.start();

        sender().send(message(Map.of("month", "aprilie 2026", "companyName", "Acme",
                "paymentSummary", "CAS: 100\n\tCASS:   40"), List.of("companyName", "paymentSummary", "month"),
                List.of()));

        var params = new ObjectMapper().readTree(sentBody.get()).path("template").path("components")
                .path(0).path("parameters");
        assertThat(params).hasSize(3);
        assertThat(params.get(0).path("text").asText()).isEqualTo("Acme");
        assertThat(params.get(1).path("text").asText()).isEqualTo("CAS: 100; CASS: 40");
        assertThat(params.get(2).path("text").asText()).isEqualTo("aprilie 2026");
    }

    @Test
    void refusesMissingOrUnexpectedVariablesInsteadOfShiftingPositions() {
        assertThatThrownBy(() -> sender().send(message(Map.of("companyName", "Acme", "extra", "x"),
                List.of("companyName", "month"), List.of())))
                .isInstanceOfSatisfying(WhatsAppProviderException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("month", "extra");
                });
    }

    @Test
    void retryAfterAFailedAttachmentDoesNotResendTheTemplate() throws Exception {
        var templateSends = new java.util.concurrent.atomic.AtomicInteger();
        var uploads = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/phone/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("\"template\"")) {
                templateSends.incrementAndGet();
                respond(exchange, 200, """
                        {"messages":[{"id":"wamid.template"}]}
                        """);
            } else {
                respond(exchange, 200, """
                        {"messages":[{"id":"wamid.media"}]}
                        """);
            }
        });
        server.createContext("/phone/media", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (uploads.incrementAndGet() == 1) {
                respond(exchange, 500, "{}"); // first attempt: upload fails after the template went out
            } else {
                respond(exchange, 200, """
                        {"id":"media-1"}
                        """);
            }
        });
        server.start();
        UUID document = UUID.randomUUID();
        when(media.load(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(document)))
                .thenReturn(new WhatsAppMediaSource.Media(document, "stat.pdf", "application/pdf", new byte[]{1, 2}));
        var msg = message(Map.of("companyName", "Acme"), List.of("companyName"),
                List.of(new WhatsAppSender.Attachment(document)));
        var progress = WhatsAppSender.DeliveryProgress.none();

        assertThatThrownBy(() -> sender().send(msg, progress))
                .isInstanceOfSatisfying(WhatsAppProviderException.class, e -> assertThat(e.isRetryable()).isTrue());
        assertThat(progress.templateMessageId()).isEqualTo("wamid.template");

        WhatsAppSender.DeliveryReceipt receipt = sender().send(msg, progress);

        assertThat(templateSends.get()).isEqualTo(1);
        assertThat(uploads.get()).isEqualTo(2);
        assertThat(progress.isAttachmentDelivered(document)).isTrue();
        assertThat(receipt.providerMessageId()).isEqualTo("wamid.template");
    }

    private MetaWhatsAppSender sender() {
        return new MetaWhatsAppSender(providers, new SecretCipher(key()), new ObjectMapper(), media,
                "http://localhost:" + server.getAddress().getPort(),
                java.net.http.HttpClient.newHttpClient());
    }

    private WhatsAppSender.Message message() {
        return new WhatsAppSender.Message("MyFinance", "+40700000000", "body", "tax_notice", "ro_RO",
                Map.of("companyName", "Acme"), List.of("companyName"), UUID.randomUUID(), List.of());
    }

    private WhatsAppSender.Message message(Map<String, String> variables, List<String> order,
                                           List<WhatsAppSender.Attachment> attachments) {
        return new WhatsAppSender.Message("MyFinance", "+40700000000", "body", "tax_notice", "ro_RO",
                variables, order, UUID.randomUUID(), attachments);
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
