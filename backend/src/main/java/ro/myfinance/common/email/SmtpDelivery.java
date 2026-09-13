package ro.myfinance.common.email;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import ro.myfinance.common.email.EmailSender.Attachment;
import ro.myfinance.common.email.EmailSender.Message;

/**
 * Builds and sends an {@link EmailSender.Message} over a supplied {@link JavaMailSender} — plain-text UTF-8
 * body, attachments as parts. Shared by the platform SMTP adapter ({@link SmtpEmailSender}) and the
 * per-tenant sender ({@link TenantAwareEmailSender}), so both build the MIME message identically.
 */
final class SmtpDelivery {

    private SmtpDelivery() {
    }

    static void send(JavaMailSender mail, Message message) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            boolean multipart = !message.attachments().isEmpty();
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, StandardCharsets.UTF_8.name());
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
        } catch (RuntimeException e) {
            throw e; // MailException from the transport — surface the real failure to the relay
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build/send email via SMTP", e);
        }
    }
}
