package ro.myfinance.access.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.common.security.Role;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    // Tenant scoping is enforced by RLS; these counts back plan-limit checks.
    long countByRoleAndStatus(Role role, UserStatus status);

    boolean existsByEmail(String email);

    java.util.Optional<AppUser> findByEmail(String email);

    java.util.List<AppUser> findByRoleIn(java.util.Collection<Role> roles);
}
