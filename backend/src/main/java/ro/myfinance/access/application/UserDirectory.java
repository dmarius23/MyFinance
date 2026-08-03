package ro.myfinance.access.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.security.Role;

/**
 * Read-only directory of platform users, exposed to other modules so they don't reach into the
 * {@code access} module's persistence adapter. All lookups are RLS-scoped to the current tenant.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

    private final AppUserRepository users;

    public UserDirectory(AppUserRepository users) {
        this.users = users;
    }

    /** A single user by id, if it exists in the current tenant. */
    public Optional<AppUser> findById(UUID id) {
        return users.findById(id);
    }

    /** The users with the given ids that exist in the current tenant. */
    public List<AppUser> findAllById(Iterable<UUID> ids) {
        return users.findAllById(ids);
    }

    /** All users in the current tenant holding any of the given roles. */
    public List<AppUser> findByRoleIn(Collection<Role> roles) {
        return users.findByRoleIn(roles);
    }
}
