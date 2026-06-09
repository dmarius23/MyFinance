package ro.myfinance.access.adapter.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import ro.myfinance.common.security.Role;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;

public final class AccessDtos {

    private AccessDtos() {
    }

    public record InviteUserRequest(@Email @NotBlank String email, String name, @NotNull Role role) {
    }

    public record SetRoleRequest(@NotNull Role role) {
    }

    public record LinkRepRequest(@NotNull UUID userId, @NotNull UUID companyId) {
    }

    public record UserResponse(UUID id, String email, String name, Role role, UserStatus status,
                               boolean mfaEnabled) {
        public static UserResponse from(AppUser u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole(),
                    u.getStatus(), u.isMfaEnabled());
        }
    }
}
