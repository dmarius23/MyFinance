package ro.myfinance.extraction.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Receipt-OCR config. {@code provider=anthropic} uses the Claude vision API ({@code api.anthropic.com},
 * US-hosted); {@code provider=bedrock} uses AWS Bedrock Claude in an EU region — the EU-resident path for
 * production (see {@code docs/MyFinance-data-privacy-residency-v1.md}). When no provider is enabled, image
 * receipts stay NEEDS_REVIEW (no-op extractor). apiKey/model/region are secrets/config via env only.
 */
@ConfigurationProperties(prefix = "myfinance.receipt")
public record ReceiptProperties(String provider, String apiKey, String model, String baseUrl,
                                String region, Double confidenceThreshold) {

    public ReceiptProperties {
        provider = (provider == null || provider.isBlank()) ? "anthropic" : provider;
        apiKey = apiKey == null ? "" : apiKey;
        model = (model == null || model.isBlank()) ? "claude-sonnet-4-5-20250929" : model;
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com" : baseUrl;
        region = region == null ? "" : region;
        confidenceThreshold = confidenceThreshold == null ? 0.6 : confidenceThreshold;
    }

    /** Anthropic Messages API path (needs an API key). */
    public boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(provider) && !apiKey.isBlank();
    }

    /** AWS Bedrock path — EU-resident; credentials/region via the AWS default chain (no api key). */
    public boolean isBedrock() {
        return "bedrock".equalsIgnoreCase(provider);
    }

    /** True when a real vision-LLM extractor is wired (so OCR may fire); false means the no-op fallback. */
    public boolean isEnabled() {
        return isAnthropic() || isBedrock();
    }
}
