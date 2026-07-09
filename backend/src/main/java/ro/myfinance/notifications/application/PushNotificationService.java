package ro.myfinance.notifications.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.notifications.adapter.persistence.PushSubscriptionRepository;
import ro.myfinance.notifications.domain.PushSubscription;

/**
 * Web Push delivery + subscription management. Reps register their browser (subscribe); every in-app
 * {@link ro.myfinance.notifications.domain.Notification} also fires a push to that user's devices via
 * {@link #dispatchToUser}. Dead subscriptions (404/410) are pruned automatically. All best-effort:
 * push never breaks the in-app feed. Tenant-scoped via RLS (subscriptions carry {@code tenant_id}).
 */
@Service
@Transactional
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final PushSubscriptionRepository subscriptions;
    private final WebPushPort push;
    private final ObjectMapper json;

    public PushNotificationService(PushSubscriptionRepository subscriptions, WebPushPort push, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.push = push;
        this.json = json;
    }

    /** What the PWA needs to subscribe: whether push is on, and the VAPID public key. */
    public record PushConfig(boolean enabled, String publicKey) {
    }

    @Transactional(readOnly = true)
    public PushConfig config() {
        return new PushConfig(push.isEnabled(), push.publicKey());
    }

    /** Register (or refresh) the current user's browser subscription. Upsert keyed by endpoint. */
    public void subscribe(String endpoint, String p256dh, String auth) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = currentUser();
        subscriptions.findByEndpoint(endpoint).ifPresentOrElse(
                existing -> existing.refreshKeys(p256dh, auth),
                () -> subscriptions.save(new PushSubscription(tenantId, userId, endpoint, p256dh, auth)));
    }

    /** Remove a browser subscription (on logout / when the user turns notifications off). */
    public void unsubscribe(String endpoint) {
        subscriptions.deleteByEndpoint(endpoint);
    }

    /**
     * Deliver a push to every device the user has registered. Best-effort: swallows failures and prunes
     * subscriptions the push service reports as gone. No-op when push is disabled or the user has none.
     */
    public void dispatchToUser(UUID userId, String title, String body) {
        if (!push.isEnabled()) {
            return;
        }
        try {
            List<PushSubscription> targets = subscriptions.findByUserId(userId);
            if (targets.isEmpty()) {
                return;
            }
            String payload = payload(title, body);
            for (PushSubscription s : targets) {
                WebPushPort.Result result = push.send(
                        new WebPushPort.Target(s.getEndpoint(), s.getP256dh(), s.getAuth()), payload);
                if (result == WebPushPort.Result.EXPIRED) {
                    subscriptions.delete(s);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Web Push dispatch failed for user {}", userId, e);
        }
    }

    private String payload(String title, String body) {
        ObjectNode node = json.createObjectNode();
        node.put("title", title);
        node.put("body", body);
        node.put("url", "/"); // the PWA opens/focuses the home on click
        return node.toString();
    }

    private UUID currentUser() {
        return TenantContext.current().map(TenantContext.Identity::userId)
                .orElseThrow(() -> new IllegalStateException("No user bound"));
    }
}
