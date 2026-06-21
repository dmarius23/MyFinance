package ro.myfinance.taxpayments.adapter.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.myfinance.taxpayments.application.EmailSender;

/**
 * Default {@link EmailSender}: logs instead of sending, so the full compose → send → history flow works
 * in dev without real delivery. Replace with the SES adapter in production (it would back off this via
 * {@link ConditionalOnMissingBean}). No money figures are invented here — the body is passed through.
 */
@Configuration
public class LoggingEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender defaultEmailSender() {
        return message -> {
            String names = message.attachments().isEmpty() ? "none"
                    : message.attachments().stream().map(EmailSender.Attachment::filename)
                        .collect(java.util.stream.Collectors.joining(", "));
            log.info("[email:dev] from=\"{}\" <{}> to={} subject={} ({} chars) attachments=[{}] — not actually sent",
                    message.fromName(), message.fromEmail(), message.to(), message.subject(),
                    message.body() == null ? 0 : message.body().length(), names);
        };
    }
}
