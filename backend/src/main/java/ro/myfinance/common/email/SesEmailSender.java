package ro.myfinance.common.email;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Production {@link EmailSender}: delivers via Amazon SES v2. Active only when
 * {@code myfinance.email.provider=ses}; otherwise {@link LoggingEmailSender} handles dev. The SES region
 * must be an EU region for the data-residency requirement (see
 * {@code docs/MyFinance-data-privacy-residency-v1.md}); credentials come from the AWS default provider
 * chain (an instance role in prod).
 *
 * <p>Every email is built as a MIME message and sent as a SES <em>raw</em> email — one path that handles
 * attachments (reports, payroll) and plain emails alike. Bodies are plain-text UTF-8 (the builders produce
 * text, not HTML). {@link #send} throws on failure so the caller records the send as FAILED.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class SesEmailSender {

    @Bean
    @ConditionalOnProperty(name = "myfinance.email.provider", havingValue = "ses")
    public EmailSender sesEmailSenderAdapter(EmailProperties props) {
        return new Sender(props);
    }

    /** The actual sender; a plain class so the SES client is built once and reused. */
    static final class Sender implements EmailSender {

        private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);
        private static final Session MIME_SESSION = Session.getInstance(new Properties());

        private final SesV2Client client;
        private final String configurationSet;

        Sender(EmailProperties props) {
            var b = SesV2Client.builder();
            if (!props.region().isBlank()) {
                b.region(Region.of(props.region()));
            }
            this.client = b.build();
            this.configurationSet = props.configurationSet();
            log.info("Email delivery: Amazon SES ({}{})",
                    props.region().isBlank() ? "default region chain" : props.region(),
                    configurationSet.isBlank() ? "" : ", config-set=" + configurationSet);
        }

        @Override
        public void send(Message message) {
            try {
                byte[] mime = toMime(message);
                var content = EmailContent.builder()
                        .raw(RawMessage.builder().data(SdkBytes.fromByteArray(mime)).build())
                        .build();
                var req = SendEmailRequest.builder().content(content);
                if (!configurationSet.isBlank()) {
                    req.configurationSetName(configurationSet);
                }
                // SES derives From / To from the MIME headers for a raw send.
                client.sendEmail(req.build());
                log.info("[email:ses] sent to={} subject={} attachments={}",
                        EmailAddresses.mask(message.to()), message.subject(), message.attachments().size());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // Wrap checked MIME errors so callers see a single delivery-failure signal.
                throw new IllegalStateException("Failed to build/send email via SES", e);
            }
        }

        /** Build the raw MIME bytes: {@code From}, {@code To}, {@code Subject}, a text body, + attachments. */
        static byte[] toMime(Message m) throws Exception {
            MimeMessage mime = new MimeMessage(MIME_SESSION);
            mime.setFrom(m.fromName() != null && !m.fromName().isBlank()
                    ? new InternetAddress(m.fromEmail(), m.fromName(), StandardCharsets.UTF_8.name())
                    : new InternetAddress(m.fromEmail()));
            mime.setRecipients(RecipientType.TO, InternetAddress.parse(m.to()));
            mime.setSubject(m.subject() == null ? "" : m.subject(), StandardCharsets.UTF_8.name());
            String body = m.body() == null ? "" : m.body();

            if (m.attachments().isEmpty()) {
                mime.setText(body, StandardCharsets.UTF_8.name());
            } else {
                MimeMultipart multipart = new MimeMultipart();
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(body, StandardCharsets.UTF_8.name());
                multipart.addBodyPart(textPart);
                for (Attachment a : m.attachments()) {
                    MimeBodyPart part = new MimeBodyPart();
                    String type = a.contentType() == null || a.contentType().isBlank()
                            ? "application/octet-stream" : a.contentType();
                    part.setDataHandler(new jakarta.activation.DataHandler(
                            new ByteArrayDataSource(a.bytes(), type)));
                    part.setFileName(a.filename());
                    multipart.addBodyPart(part);
                }
                mime.setContent(multipart);
            }
            mime.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return out.toByteArray();
        }
    }
}
