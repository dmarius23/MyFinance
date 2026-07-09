package ro.myfinance.notifications.adapter.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.notifications.application.PushNotificationService;
import ro.myfinance.notifications.application.PushNotificationService.PushConfig;

/**
 * Representative PWA Web Push endpoints: fetch the VAPID public key, register a browser subscription, and
 * remove it (on logout / opt-out). Scoped to the representative role; the subscription is bound to the
 * caller's user + tenant server-side (never trusts the client for identity).
 */
@RestController
@PreAuthorize("hasRole('REPRESENTATIVE')")
public class PortalPushController {

    private final PushNotificationService push;

    public PortalPushController(PushNotificationService push) {
        this.push = push;
    }

    @GetMapping("/api/v1/portal/push/config")
    public PushConfig config() {
        return push.config();
    }

    @PostMapping("/api/v1/portal/push/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@RequestBody SubscribeRequest req) {
        push.subscribe(req.endpoint(), req.p256dh(), req.auth());
    }

    @DeleteMapping("/api/v1/portal/push/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestBody UnsubscribeRequest req) {
        push.unsubscribe(req.endpoint());
    }

    public record SubscribeRequest(@NotBlank String endpoint, @NotBlank String p256dh, @NotBlank String auth) {
    }

    public record UnsubscribeRequest(@NotBlank String endpoint) {
    }
}
