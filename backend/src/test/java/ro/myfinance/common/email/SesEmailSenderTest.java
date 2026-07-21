package ro.myfinance.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MIME assembly for the SES raw-send path (the sender's only branch-y logic, testable without AWS). */
class SesEmailSenderTest {

    @Test
    void plainEmailHasHeadersAndTextBody() throws Exception {
        var msg = EmailSender.Message.of("Maria Contabil", "firma@contabil.ro", "ion@client.ro",
                "Sume de plată — iulie 2026", "Bună ziua, aveți de plată 1.234 lei.");

        String mime = new String(SesEmailSender.Sender.toMime(msg), StandardCharsets.UTF_8);

        assertThat(mime).contains("To: ion@client.ro");
        assertThat(mime).contains("firma@contabil.ro");
        assertThat(mime).contains("Subject:");          // subject present (encoded for non-ASCII)
        assertThat(mime).doesNotContain("multipart");   // no attachments → simple text part
    }

    @Test
    void emailWithAttachmentIsMultipartAndNamesTheFile() throws Exception {
        var pdf = new EmailSender.Attachment("raport-iulie.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
        var msg = new EmailSender.Message("Maria", "firma@contabil.ro", "ion@client.ro",
                "Raport lunar", "Atașat raportul.", List.of(pdf));

        String mime = new String(SesEmailSender.Sender.toMime(msg), StandardCharsets.UTF_8);

        assertThat(mime).containsIgnoringCase("multipart");
        assertThat(mime).contains("raport-iulie.pdf");
        assertThat(mime).contains("application/pdf");
    }

    @Test
    void masksRecipientForLogs() {
        assertThat(EmailAddresses.mask("alexandru.popescu@firma.ro")).isEqualTo("a***@firma.ro");
        assertThat(EmailAddresses.mask("")).isEqualTo("<none>");
        assertThat(EmailAddresses.mask("bogus")).isEqualTo("***");
    }
}
