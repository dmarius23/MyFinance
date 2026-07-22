package ro.myfinance.settings.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoints + politeness knobs for the ANAF IBAN scraper. Defaults point at the live public catalogue;
 * override in tests or if ANAF moves the static host.
 *
 * <pre>
 * myfinance.anaf-iban.index-url=...coduri_iban          # the county index page
 * myfinance.anaf-iban.base-url=...static.../iban2014     # where the .htm + .pdf files live
 * myfinance.anaf-iban.request-timeout-seconds=30
 * myfinance.anaf-iban.delay-millis=200                   # pause between PDF downloads (be polite)
 * </pre>
 */
@ConfigurationProperties(prefix = "myfinance.anaf-iban")
public record AnafIbanProperties(String indexUrl, String baseUrl, Integer requestTimeoutSeconds,
                                 Long delayMillis, String userAgent) {

    private static final String DEFAULT_INDEX =
            "https://www.anaf.ro/anaf/internet/ANAF/asistenta_contribuabili/plata_oblig_fiscale/coduri_iban";
    private static final String DEFAULT_BASE =
            "https://static.anaf.ro/static/10/Anaf/AsistentaContribuabili_r/iban2014";

    public AnafIbanProperties {
        indexUrl = (indexUrl == null || indexUrl.isBlank()) ? DEFAULT_INDEX : indexUrl;
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE : baseUrl;
        requestTimeoutSeconds = requestTimeoutSeconds == null ? 30 : requestTimeoutSeconds;
        delayMillis = delayMillis == null ? 200L : delayMillis;
        userAgent = (userAgent == null || userAgent.isBlank())
                ? "MyFinance-IBAN-Sync/1.0 (+https://myfinance.ro)" : userAgent;
    }
}
