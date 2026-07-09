package ro.myfinance.notifications.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push (VAPID) config. When the key pair is blank, push is disabled (a no-op sender) and only the
 * in-app feed is used. Keys are secrets provided via env only. {@code subject} is the VAPID contact
 * (a {@code mailto:} or {@code https:} URL) the push service can reach if a subscription misbehaves.
 */
@ConfigurationProperties(prefix = "myfinance.push.vapid")
public record PushProperties(String publicKey, String privateKey, String subject) {

    public PushProperties {
        publicKey = publicKey == null ? "" : publicKey.trim();
        privateKey = privateKey == null ? "" : privateKey.trim();
        subject = (subject == null || subject.isBlank()) ? "mailto:noreply@myfinance.local" : subject.trim();
    }

    /** Push is active only when both VAPID keys are configured. */
    public boolean isEnabled() {
        return !publicKey.isBlank() && !privateKey.isBlank();
    }
}
