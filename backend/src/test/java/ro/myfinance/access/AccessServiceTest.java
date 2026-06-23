package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.application.AccessService;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;

/** Activate re-enables a user; the last active admin can't be deactivated. */
class AccessServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RepresentativeLinkRepository links = mock(RepresentativeLinkRepository.class);
    private final ro.myfinance.access.application.UserInviter inviter =
            mock(ro.myfinance.access.application.UserInviter.class);
    private final AccessService service = new AccessService(users, links, inviter);

    @Test
    void activateSetsStatusActive() {
        UUID id = UUID.randomUUID();
        AppUser u = new AppUser(id, UUID.randomUUID(), "x@firma.ro", "Ana", Role.EMPLOYEE);
        u.setStatus(UserStatus.INACTIVE);
        when(users.findById(id)).thenReturn(Optional.of(u));

        assertThat(service.activate(id).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void inviteUsesSupabaseIdAndMarksInvited() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            UUID externalId = UUID.randomUUID();
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.invite(org.mockito.ArgumentMatchers.eq("nou@firma.ro"), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ro.myfinance.access.application.UserInviter.InvitedUser(externalId));
            when(users.save(org.mockito.ArgumentMatchers.any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

            AppUser u = service.inviteUser("nou@firma.ro", "Nou Contabil", Role.EMPLOYEE);
            assertThat(u.getId()).isEqualTo(externalId); // app_user id == Supabase subject → recognized on login
            assertThat(u.getStatus()).isEqualTo(UserStatus.INVITED);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void inviteRejectsNonStaffRole() {
        assertThatThrownBy(() -> service.inviteUser("x@y.ro", "X", Role.REPRESENTATIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotDeactivateLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        AppUser admin = new AppUser(id, UUID.randomUUID(), "admin@firma.ro", "Admin", Role.TENANT_ADMIN);
        when(users.findById(id)).thenReturn(Optional.of(admin));
        when(users.countByRoleAndStatus(Role.TENANT_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.deactivate(id)).isInstanceOf(ConflictException.class);
    }
}
