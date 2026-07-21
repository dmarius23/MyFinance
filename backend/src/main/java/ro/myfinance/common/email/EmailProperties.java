package ro.myfinance.common.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email delivery config. {@code provider=ses} selects the Amazon SES adapter (must be an EU region for the
 * data-residency requirement — see {@code docs/MyFinance-data-privacy-residency-v1.md}); anything else
 * (default) keeps the dev logging no-op. {@code region} + credentials come from the AWS default provider
 * chain (instance role in prod) unless {@code region} is set here. {@code configurationSet} is an optional
 * SES configuration set for bounce/complaint tracking.
 */
@ConfigurationProperties(prefix = "myfinance.email")
public record EmailProperties(String provider, String region, String configurationSet) {

    public EmailProperties {
        provider = (provider == null || provider.isBlank()) ? "logging" : provider;
        region = region == null ? "" : region;
        configurationSet = configurationSet == null ? "" : configurationSet;
    }
}
