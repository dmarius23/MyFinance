package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.application.SignInRecorder;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.security.Role;

/**
 * First authenticated request promotes a user off the "never signed in" statuses. Without this nothing
 * ever cleared INVITED, so the invite state could not be trusted (see UserStatus).
 */
class SignInRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SignInRecorder recorder =
            new SignInRecorder(users, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID userId = UUID.randomUUID();

    private AppUser user(UserStatus status) {
        AppUser u = new AppUser(userId, UUID.randomUUID(), "rep@client.ro", "Rep One", Role.REPRESENTATIVE);
        u.setStatus(status);
        when(users.findById(userId)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void promotesPendingInviteToActiveAndStampsLastLogin() {
        AppUser u = user(UserStatus.PENDING_INVITE);

        recorder.recordSignIn(userId);

        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.getLastLogin()).isEqualTo(NOW);
    }

    @Test
    void promotesInvitedToActive() {
        AppUser u = user(UserStatus.INVITED);

        recorder.recordSignIn(userId);

        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void leavesADeactivatedUserInactive() {
        AppUser u = user(UserStatus.INACTIVE);

        recorder.recordSignIn(userId);

        // A deactivated account must not resurrect itself by presenting a still-valid token.
        assertThat(u.getStatus()).isEqualTo(UserStatus.INACTIVE);
    }

    @Test
    void refreshesAStaleLastLoginForAnAlreadyActiveUser() {
        AppUser u = user(UserStatus.ACTIVE);
        u.setLastLogin(NOW.minus(java.time.Duration.ofDays(7)));

        recorder.recordSignIn(userId);

        assertThat(u.getLastLogin()).isEqualTo(NOW);
    }

    @Test
    void doesNotRewriteARecentLastLogin() {
        AppUser u = user(UserStatus.ACTIVE);
        Instant recent = NOW.minusSeconds(30);
        u.setLastLogin(recent);

        recorder.recordSignIn(userId);

        // Bootstrap runs on every app load; re-stamping each time would be a write per page view.
        assertThat(u.getLastLogin()).isEqualTo(recent);
    }

    @Test
    void ignoresAnUnknownUser() {
        when(users.findById(any())).thenReturn(Optional.empty());

        recorder.recordSignIn(userId); // must not throw — bootstrap must never 500 over this
    }
}
