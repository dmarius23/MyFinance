package ro.myfinance.ingestion.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google OAuth web-client configuration. Client secret is environment-only, never tenant data. */
@ConfigurationProperties(prefix = "myfinance.ingestion.gmail")
public record GmailOAuthProperties(String clientId, String clientSecret, String redirectUri,
                                   String pubSubTopic, String webhookToken,
                                   String pubSubAudience, String pubSubServiceAccount) {

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }
}
