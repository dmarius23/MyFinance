package ro.myfinance.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.UserStatus;

/**
 * Marks a user as having actually used the app. Until this existed nothing ever moved a user off
 * {@code INVITED}, so the status told you only that the row had once been created — a rep who had been
 * working in the portal for months still read "Invitat", which is why the invite state could not be
 * trusted.
 *
 * <p>Runs in its own transaction ({@code REQUIRES_NEW}) because the callers are read-only request
 * transactions (e.g. {@code PortalService.me()}), which cannot flush a write.
 */
@Component
public class SignInRecorder {

    /** Don't write on every bootstrap call — only refresh a stale {@code last_login}. */
    static final Duration TOUCH_INTERVAL = Duration.ofHours(12);

    private final AppUserRepository users;
    private final Clock clock;

    public SignInRecorder(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * Record that {@code userId} is making an authenticated request: promote them to {@code ACTIVE} the
     * first time, and keep {@code last_login} roughly current.
     *
     * <p>{@code INACTIVE} is deliberately left alone — a deactivated account must never resurrect itself
     * just by presenting a still-valid token; only an admin re-activates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSignIn(UUID userId) {
        users.findById(userId).ifPresent(user -> {
            Instant now = clock.instant();
            if (user.getStatus() == UserStatus.PENDING_INVITE || user.getStatus() == UserStatus.INVITED) {
                user.setStatus(UserStatus.ACTIVE);
                user.setLastLogin(now);
                return;
            }
            Instant seen = user.getLastLogin();
            if (seen == null || seen.isBefore(now.minus(TOUCH_INTERVAL))) {
                user.setLastLogin(now);
            }
        });
    }
}
