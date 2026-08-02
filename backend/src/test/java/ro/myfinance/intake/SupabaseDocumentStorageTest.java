package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.adapter.external.SupabaseDocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/**
 * Round-trip contract of the Supabase Storage adapter (store → retrieve → delete) against a mock storage
 * REST endpoint — including the key point that the multi-segment {@code storage_key}'s slashes stay literal
 * in the URL (not {@code %2F}), and that a missing object maps to 404 / delete is idempotent.
 */
class SupabaseDocumentStorageTest {

    // A realistic sanitized storage_key: tenant / company / period / uuid-name.
    private static final String KEY = "aaaa1111/bbbb2222/2026-08/1234-bon.pdf";
    private static final String OBJECT_URL =
            "https://proj.supabase.co/storage/v1/object/documents/" + KEY; // literal slashes

    private SupabaseDocumentStorage storage(RestClient.Builder builder) {
        var props = new SupabaseProperties("https://proj.supabase.co", "test-service-key",
                "https://proj.supabase.co/auth/v1");
        return new SupabaseDocumentStorage(props, "documents", builder);
    }

    @Test
    void storeUploadsToTheObjectPathWithLiteralSlashes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OBJECT_URL))            // proves NOT %2F-encoded
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-key"))
                .andExpect(header("x-upsert", "true"))
                .andRespond(withSuccess());

        StoredObject stored = storage(builder).store(KEY, "PDFBYTES".getBytes(StandardCharsets.UTF_8),
                "application/pdf");

        assertThat(stored.key()).isEqualTo(KEY);
        server.verify();
    }

    @Test
    void retrieveReturnsTheStoredBytes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OBJECT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("PDFBYTES".getBytes(StandardCharsets.UTF_8),
                        MediaType.APPLICATION_OCTET_STREAM));

        byte[] bytes = storage(builder).retrieve(KEY);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("PDFBYTES");
        server.verify();
    }

    @Test
    void retrieveMissingObjectMapsTo404() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OBJECT_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> storage(builder).retrieve(KEY)).isInstanceOf(NotFoundException.class);
        server.verify();
    }

    @Test
    void deleteIsIdempotentWhenTheObjectIsAlreadyGone() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OBJECT_URL)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withResourceNotFound());

        storage(builder).delete(KEY); // must not throw

        server.verify();
    }
}
