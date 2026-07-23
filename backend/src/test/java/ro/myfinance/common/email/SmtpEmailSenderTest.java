package ro.myfinance.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/** MIME assembly for the SMTP path — the sender's only logic, testable with a mocked JavaMailSender. */
class SmtpEmailSenderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final EmailSender sender = new SmtpEmailSender.Sender(mail);

    @BeforeEach
    void freshMimeMessage() {
        // JavaMailSender.createMimeMessage() → a blank MimeMessage the helper populates.
        when(mail.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private static String render(MimeMessage mime) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mime.writeTo(out); // writeTo auto-saves the message
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainEmailCarriesHeadersAndTextBody() throws Exception {
        var msg = EmailSender.Message.of("Maria Contabil", "firma@contabil.ro", "ion@client.ro",
                "Sume de plată — iulie 2026", "Bună ziua, aveți de plată 1.234 lei.");

        sender.send(msg);

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        String mime = render(cap.getValue());
        assertThat(mime).contains("To: ion@client.ro");
        assertThat(mime).contains("firma@contabil.ro");   // From address
        assertThat(mime).contains("Subject:");            // present (encoded for non-ASCII)
        assertThat(mime).doesNotContain("multipart");     // no attachments → simple text
    }

    @Test
    void emailWithAttachmentIsMultipartAndNamesTheFile() throws Exception {
        var pdf = new EmailSender.Attachment("raport-iulie.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
        var msg = new EmailSender.Message("Maria", "firma@contabil.ro", "ion@client.ro",
                "Raport lunar", "Atașat raportul.", List.of(pdf));

        sender.send(msg);

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        String mime = render(cap.getValue());
        assertThat(mime).containsIgnoringCase("multipart");
        assertThat(mime).contains("raport-iulie.pdf");
    }

    @Test
    void transportFailurePropagatesSoTheRelayRetries() {
        doThrow(new MailSendException("smtp down")).when(mail).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send(
                EmailSender.Message.of("F", "f@x.ro", "t@x.ro", "S", "b")))
                .isInstanceOf(RuntimeException.class);
    }
}
