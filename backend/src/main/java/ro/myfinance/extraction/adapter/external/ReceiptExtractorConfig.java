package ro.myfinance.extraction.adapter.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ro.myfinance.extraction.application.ReceiptExtractor;

/**
 * Selects the receipt extractor from config. provider=anthropic with a key → Claude vision; otherwise
 * a no-op (image receipts stay NEEDS_REVIEW). Explicit factory so a blank key falls back cleanly.
 */
@Configuration
@EnableConfigurationProperties(ReceiptProperties.class)
public class ReceiptExtractorConfig {

    private static final Logger log = LoggerFactory.getLogger(ReceiptExtractorConfig.class);

    @Bean
    ReceiptExtractor receiptExtractor(ReceiptProperties props, RestClient.Builder builder) {
        if (props.isAnthropic()) {
            log.info("Receipt OCR: Anthropic vision (model={})", props.model());
            return new AnthropicReceiptExtractor(props, builder);
        }
        log.info("Receipt OCR: disabled (no provider/key) — image receipts will be marked NEEDS_REVIEW");
        return new NoopReceiptExtractor();
    }
}
