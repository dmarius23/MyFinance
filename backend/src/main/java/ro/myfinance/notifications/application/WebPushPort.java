package ro.myfinance.notifications.application;

/**
 * Port for sending one encrypted Web Push message to a single browser subscription. The adapter holds
 * the VAPID key pair; the application layer supplies the target keys and the JSON payload. Kept tiny so
 * the delivery/persistence logic (which subscriptions, pruning) stays in the application service.
 */
public interface WebPushPort {

    /** Outcome of a single delivery, used by the caller to prune dead subscriptions. */
    enum Result {
        /** Accepted by the push service. */
        SENT,
        /** The subscription is gone (404/410) — the caller should delete the row. */
        EXPIRED,
        /** Transient/other failure — keep the row, try again next time. */
        FAILED,
        /** Push is not configured (no VAPID keys) — nothing was sent. */
        DISABLED
    }

    /** One browser subscription's endpoint + client public keys (base64url). */
    record Target(String endpoint, String p256dh, String auth) {
    }

    /** True when VAPID keys are configured and pushes can actually be delivered. */
    boolean isEnabled();

    /** The VAPID public key (base64url) the PWA needs to subscribe; empty when disabled. */
    String publicKey();

    /** Encrypt and POST {@code payloadJson} to the subscription. Never throws — returns a {@link Result}. */
    Result send(Target target, String payloadJson);
}
