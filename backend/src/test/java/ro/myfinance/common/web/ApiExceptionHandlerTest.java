package ro.myfinance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsMaxUploadSizeExceededTo413() {
        ProblemDetail problem = handler.handleTooLarge(new MaxUploadSizeExceededException(25L * 1024 * 1024));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        // Generic message — must not advertise the exact configured limit.
        assertThat(problem.getDetail()).isEqualTo("Uploaded file exceeds the maximum allowed size");
    }

    /**
     * The real production failure: Gmail refusing an account password on "send test email". Spring's own
     * message is only "Authentication failed" — the fix the admin needs ("use an app password") lives in
     * the nested jakarta.mail cause, so the response must carry it or it is no better than the old 500.
     */
    @Test
    void mailAuthFailureIs409AndKeepsTheProvidersExplanation() {
        MailAuthenticationException ex = new MailAuthenticationException("Authentication failed",
                new jakarta.mail.AuthenticationFailedException(
                        "534-5.7.9 Application-specific password required. For more information, go to"));

        ProblemDetail problem = handler.handleMailAuth(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail())
                .contains("rejected these credentials")
                .contains("Application-specific password required");
    }

    /** Transport failures are upstream problems, not the caller's fault. */
    @Test
    void otherMailFailuresAre502() {
        MailSendException ex = new MailSendException("Mail server connection failed",
                new jakarta.mail.MessagingException("Unknown SMTP host: smtp.typo.example"));

        ProblemDetail problem = handler.handleMailFailure(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problem.getDetail()).contains("Unknown SMTP host");
    }

    /** A cause-less exception must not render "null" into the response. */
    @Test
    void mailFailureDegradesCleanlyWithoutACause() {
        ProblemDetail problem =
                handler.handleMailAuth(new MailAuthenticationException("Authentication failed"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).doesNotContain("null");
    }

    /** Multi-line SMTP replies are flattened and bounded so the response stays one readable line. */
    @Test
    void flattensAndCapsALongProviderReply() {
        String noisy = "535-5.7.8 Username and Password not accepted.\n535 5.7.8 Learn more at\n"
                + "https://support.google.com/mail/?p=BadCredentials " + "x".repeat(400);

        ProblemDetail problem = handler.handleMailAuth(
                new MailAuthenticationException("Authentication failed", new RuntimeException(noisy)));

        assertThat(problem.getDetail())
                .doesNotContain("\n")
                .contains("Username and Password not accepted")
                .hasSizeLessThan(400);
    }

    /** A server-side config gap is not the tenant's fault — 503, naming the setting so it is fixable. */
    @Test
    void misconfigurationIs503NamingTheSetting() {
        ProblemDetail problem = handler.handleMisconfigured(
                new MisconfiguredException("Server secret key (MYFINANCE_SECRET_KEY) is not configured"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getDetail()).contains("MYFINANCE_SECRET_KEY");
    }
}
