package ro.myfinance.extraction.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Receipt-OCR config. provider=anthropic uses the Claude vision API (the Bedrock EU adapter for prod
 * shares this port). When the API key is blank, image receipts stay NEEDS_REVIEW (no-op extractor).
 * apiKey/model are secrets/config via env only.
 */
@ConfigurationProperties(prefix = "myfinance.receipt")
public record ReceiptProperties(String provider, String apiKey, String model, String baseUrl,
                                Double confidenceThreshold) {

    public ReceiptProperties {
        provider = (provider == null || provider.isBlank()) ? "anthropic" : provider;
        apiKey = apiKey == null ? "" : apiKey;
        model = (model == null || model.isBlank()) ? "claude-sonnet-4-5-20250929" : model;
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com" : baseUrl;
        confidenceThreshold = confidenceThreshold == null ? 0.6 : confidenceThreshold;
    }

    public boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(provider) && !apiKey.isBlank();
    }
}
