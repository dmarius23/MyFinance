package ro.myfinance.intake.adapter.external;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/** Stores documents in a Supabase Storage bucket via the storage REST API. Prod adapter. */
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
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return new StoredObject(key, bytes.length);
    }

    @Override
    public byte[] retrieve(String key) {
        return client.get()
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .retrieve()
                .body(byte[].class);
    }

    @Override
    public void delete(String key) {
        client.delete()
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .retrieve()
                .toBodilessEntity();
    }
}
