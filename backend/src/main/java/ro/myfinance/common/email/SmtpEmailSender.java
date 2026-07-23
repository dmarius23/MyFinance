package ro.myfinance.common.email;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Production {@link EmailSender} that delivers over plain SMTP via Spring's {@link JavaMailSender} — the
 * provider-agnostic path for any EU transactional-email service (Brevo, Scaleway TEM, Mailjet, Infomaniak,
 * …) without an AWS account, keeping delivery inside the EU per
 * {@code docs/MyFinance-data-privacy-residency-v1.md}. Active only when
 * {@code myfinance.email.provider=smtp}; the SMTP host/credentials come from Spring's {@code spring.mail.*}
 * (so a blank {@code spring.mail.host} means no {@code JavaMailSender} and this adapter simply isn't wired).
 *
 * <p>Bodies are plain-text UTF-8 (the builders produce text, not HTML); attachments (report PDFs, payroll
 * files) are added as parts. {@link #send} throws on failure so the outbox relay records the send as
 * FAILED and retries.
 */
@Configuration
public class SmtpEmailSender {

    @Bean
    @ConditionalOnProperty(name = "myfinance.email.provider", havingValue = "smtp")
    public EmailSender smtpEmailSenderAdapter(JavaMailSender mailSender) {
        return new Sender(mailSender);
    }

    /** The actual sender; a plain class so it can be unit-tested with a mocked {@link JavaMailSender}. */
    static final class Sender implements EmailSender {

        private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

        private final JavaMailSender mail;

        Sender(JavaMailSender mail) {
            this.mail = mail;
            log.info("Email delivery: SMTP (JavaMailSender)");
        }

        @Override
        public void send(Message message) {
            try {
                MimeMessage mime = mail.createMimeMessage();
                boolean multipart = !message.attachments().isEmpty();
                MimeMessageHelper helper =
                        new MimeMessageHelper(mime, multipart, StandardCharsets.UTF_8.name());

                if (message.fromName() != null && !message.fromName().isBlank()) {
                    helper.setFrom(message.fromEmail(), message.fromName());
                } else {
                    helper.setFrom(message.fromEmail());
                }
                helper.setTo(InternetAddress.parse(message.to())); // tolerant of a comma-separated list
                helper.setSubject(message.subject() == null ? "" : message.subject());
                helper.setText(message.body() == null ? "" : message.body(), false); // plain text

                for (Attachment a : message.attachments()) {
                    String type = a.contentType() == null || a.contentType().isBlank()
                            ? "application/octet-stream" : a.contentType();
                    helper.addAttachment(a.filename(), new ByteArrayResource(a.bytes()), type);
                }

                mail.send(mime);
                log.info("[email:smtp] sent to={} subject={} attachments={}",
                        EmailAddresses.mask(message.to()), message.subject(), message.attachments().size());
            } catch (RuntimeException e) {
                throw e; // MailException from the transport — let the relay see the real failure
            } catch (Exception e) {
                // Wrap checked MIME/address errors so callers see a single delivery-failure signal.
                throw new IllegalStateException("Failed to build/send email via SMTP", e);
            }
        }
    }
}
