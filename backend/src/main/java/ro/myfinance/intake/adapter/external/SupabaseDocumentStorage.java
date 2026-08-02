package ro.myfinance.intake.adapter.external;

import java.net.URI;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/**
 * Stores documents in a Supabase Storage bucket via the storage REST API — the durable, multi-instance
 * production adapter (objects encrypted at rest by Supabase's S3 backend). The {@code storage_key} is a
 * multi-segment path ({@code tenant/company/period/uuid-name}); it is emitted as real URL path segments
 * (each encoded, slashes kept literal) rather than a single template variable, so it isn't mangled into
 * {@code %2F}. Downloads are proxied through the API by {@code DocumentService.getContent}, which re-checks
 * ownership under RLS first. Missing objects map to 404 (not 500); delete is idempotent.
 */
public class SupabaseDocumentStorage implements DocumentStorage {

    private final RestClient client;
    private final String bucket;

    public SupabaseDocumentStorage(SupabaseProperties props, String bucket, RestClient.Builder builder) {
        this.bucket = bucket;
        this.client = builder
                .baseUrl(props.url())
                .defaultHeader("Authorization", "Bearer " + props.serviceRoleKey())
                .build();
    }

    @Override
    public StoredObject store(String key, byte[] bytes, String contentType) {
        client.post()
                .uri(b -> objectUri(b, key))
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return new StoredObject(key, bytes.length);
    }

    @Override
    public byte[] retrieve(String key) {
        try {
            return client.get()
                    .uri(b -> objectUri(b, key))
                    .retrieve()
                    .body(byte[].class);
        } catch (HttpClientErrorException.NotFound e) {
            // The DB row exists but the object is gone → 404, not 500 (matches the local adapter).
            throw new NotFoundException("Document file not found: " + key);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.delete()
                    .uri(b -> objectUri(b, key))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // Idempotent: the object is already absent — nothing to delete.
        }
    }

    /** {@code /storage/v1/object/{bucket}/<key path>} with the key's slashes preserved as segment boundaries. */
    private URI objectUri(UriBuilder b, String key) {
        String[] segments = Arrays.stream(key.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return b.path("/storage/v1/object/{bucket}").pathSegment(segments).build(bucket);
    }
}
