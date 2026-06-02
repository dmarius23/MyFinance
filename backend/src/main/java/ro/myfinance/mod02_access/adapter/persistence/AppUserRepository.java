package ro.myfinance.mod02_access.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.common.security.Role;
import ro.myfinance.mod02_access.domain.AppUser;
import ro.myfinance.mod02_access.domain.UserStatus;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    // Tenant scoping is enforced by RLS; these counts back plan-limit checks.
    long countByRoleAndStatus(Role role, UserStatus status);
}
