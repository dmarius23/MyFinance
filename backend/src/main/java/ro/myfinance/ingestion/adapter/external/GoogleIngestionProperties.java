package ro.myfinance.ingestion.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-account credentials for Google Drive ingestion. The key lives in env/config (never the DB);
 * the admin shares their Drive folder with {@code clientEmail} and pastes the folder id into the app.
 * Blank/absent → the Drive connector reports "not configured" instead of failing the whole context.
 *
 * <pre>
 * myfinance.ingestion.google.client-email=svc@project.iam.gserviceaccount.com
 * myfinance.ingestion.google.private-key=-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----
 * </pre>
 */
@ConfigurationProperties(prefix = "myfinance.ingestion.google")
public record GoogleIngestionProperties(String clientEmail, String privateKey) {

    public boolean isConfigured() {
        return clientEmail != null && !clientEmail.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
