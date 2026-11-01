package ro.myfinance.access.adapter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.PasswordResetService;

/**
 * PUBLIC endpoint — the caller is locked out of their account, so there is no token to present. Listed in
 * {@code SecurityConfig}'s permitAll set alongside the Gmail Pub/Sub webhook.
 *
 * <p><b>Always 204, whatever happens.</b> Unknown address, deactivated user, unconfigured tenant provider,
 * dead SMTP — all indistinguishable from success. Anything else turns this into an account-enumeration
 * oracle, and it is reachable by anyone on the internet. The response carries no body, and in particular
 * never the reset link itself.
 *
 * <p>Abuse is bounded by {@code RateLimitFilter}, which classifies this path as email-sending and keys the
 * bucket on the remote address (there being no authenticated caller to key on).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        service.requestReset(request.email());
    }

    /** Validated only for shape — a well-formed address that matches nobody still returns 204. */
    public record ForgotPasswordRequest(@Email @NotBlank String email) {
    }
}
