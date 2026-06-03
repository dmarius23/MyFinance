package ro.myfinance.mod02_access.adapter.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.mod02_access.domain.AppUser;
import ro.myfinance.mod02_access.domain.UserStatus;

public final class RepresentativeDtos {

    private RepresentativeDtos() {
    }

    public record InviteRepresentativeRequest(@Email @NotBlank String email, String name) {
    }

    public record RepresentativeResponse(UUID id, String email, String name, UserStatus status) {
        public static RepresentativeResponse from(AppUser u) {
            return new RepresentativeResponse(u.getId(), u.getEmail(), u.getName(), u.getStatus());
        }
    }
}
