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
        return (to, subject, body) -> log.info("[email:dev] to={} subject={} ({} chars) — not actually sent",
                to, subject, body == null ? 0 : body.length());
    }
}
