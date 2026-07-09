package ro.myfinance.notifications.adapter.external;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import ro.myfinance.notifications.application.WebPushPort;

/**
 * Web Push adapter over the {@code nl.martijndwars:web-push} library (VAPID + RFC-8291 AES128GCM).
 * A single bean: when VAPID keys are configured it encrypts and delivers; when they are blank it
 * reports {@link Result#DISABLED} so callers skip push and rely on the in-app feed. Never throws.
 */
@Component
@EnableConfigurationProperties(PushProperties.class)
public class MartijndwarsWebPushPort implements WebPushPort {

    private static final Logger log = LoggerFactory.getLogger(MartijndwarsWebPushPort.class);

    private final PushProperties props;
    private final PushService pushService; // null when disabled or misconfigured

    public MartijndwarsWebPushPort(PushProperties props) {
        this.props = props;
        PushService svc = null;
        if (props.isEnabled()) {
            try {
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
                svc = new PushService(props.publicKey(), props.privateKey(), props.subject());
                log.info("Web Push: enabled (VAPID configured)");
            } catch (Exception e) {
                log.warn("Web Push: VAPID keys present but invalid — push disabled", e);
            }
        } else {
            log.info("Web Push: disabled (no VAPID keys) — in-app notifications only");
        }
        this.pushService = svc;
    }

    @Override
    public boolean isEnabled() {
        return pushService != null;
    }

    @Override
    public String publicKey() {
        return pushService != null ? props.publicKey() : "";
    }

    @Override
    public Result send(Target target, String payloadJson) {
        if (pushService == null) {
            return Result.DISABLED;
        }
        try {
            Notification notification = new Notification(target.endpoint(), target.p256dh(), target.auth(),
                    payloadJson.getBytes(StandardCharsets.UTF_8));
            HttpResponse response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            if (status == 404 || status == 410) {
                return Result.EXPIRED; // subscription gone — caller prunes it
            }
            log.warn("Web Push: delivery returned status {}", status);
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("Web Push: delivery failed for endpoint {}", safeEndpoint(target.endpoint()), e);
            return Result.FAILED;
        }
    }

    /** Log only the push-service host, never the full endpoint (it is a bearer-like capability URL). */
    private static String safeEndpoint(String endpoint) {
        try {
            return java.net.URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
