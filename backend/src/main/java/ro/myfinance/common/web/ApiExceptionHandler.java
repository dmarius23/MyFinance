package ro.myfinance.common.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps exceptions to RFC-7807 {@link ProblemDetail} responses. Never leaks stack traces or PII;
 * validation errors are surfaced field-by-field.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Server-side configuration gap (e.g. MYFINANCE_SECRET_KEY unset). The message names the missing
     * setting — never its value — so an operator can act on it instead of digging into an opaque 500.
     */
    @ExceptionHandler(MisconfiguredException.class)
    ProblemDetail handleMisconfigured(MisconfiguredException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * The mail provider rejected the tenant's credentials (synchronous "send test email"). 409, because
     * the firm fixes it in Settings. The provider's own text is what makes this actionable — e.g. Gmail's
     * "534-5.7.9 Application-specific password required" tells the admin exactly what to do — so we append
     * the ROOT cause: Spring's own message is only "Authentication failed".
     */
    @ExceptionHandler(MailAuthenticationException.class)
    ProblemDetail handleMailAuth(MailAuthenticationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The email provider rejected these credentials. " + rootCauseMessage(ex));
    }

    /**
     * Any other mail transport failure (unknown host, refused connection, timeout). 502 — the request was
     * fine, the upstream provider was not. Must stay BELOW the auth handler: Spring picks the most
     * specific match, and MailAuthenticationException extends MailException.
     */
    @ExceptionHandler(MailException.class)
    ProblemDetail handleMailFailure(MailException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The email provider could not be reached. " + rootCauseMessage(ex));
    }

    /**
     * Deepest cause's message, flattened to one line and capped. Mail exceptions nest the provider's SMTP
     * reply, which is the only actionable part; the wrapper says nothing useful. Never includes the
     * credentials themselves — jakarta.mail puts only the server's reply text in the message.
     */
    private static String rootCauseMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    /**
     * Servlet-layer multipart cap (spring.servlet.multipart.max-file-size / max-request-size) tripped
     * before the request reaches a controller. Return a clean 413 instead of a leaked stack trace; the
     * message stays generic so we don't advertise the exact limit.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleTooLarge(MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file exceeds the maximum allowed size");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        List<String> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.add(fe.getField() + ": " + fe.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }
}
