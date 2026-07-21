package ro.myfinance.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link EmailSender} for dev/test: logs instead of sending, so the full compose → send → history
 * flow works without real delivery. Active unless {@code myfinance.email.provider=ses} selects
 * {@link SesEmailSender}. Recipients are masked in the log line (no PII). No money figures are invented —
 * the body is passed through untouched.
 */
@Configuration
public class LoggingEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    @ConditionalOnProperty(name = "myfinance.email.provider", havingValue = "logging", matchIfMissing = true)
    public EmailSender defaultEmailSender() {
        return message -> {
            String names = message.attachments().isEmpty() ? "none"
                    : message.attachments().stream().map(EmailSender.Attachment::filename)
                        .collect(java.util.stream.Collectors.joining(", "));
            log.info("[email:dev] from=\"{}\" <{}> to={} subject={} ({} chars) attachments=[{}] — not actually sent",
                    message.fromName(), message.fromEmail(), EmailAddresses.mask(message.to()),
                    message.subject(), message.body() == null ? 0 : message.body().length(), names);
        };
    }
}
