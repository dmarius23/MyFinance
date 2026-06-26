package ro.myfinance.access.adapter.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;

public final class RepresentativeDtos {

    private RepresentativeDtos() {
    }

    public record InviteRepresentativeRequest(@NotBlank String name, @Email @NotBlank String email, String phone) {
    }

    public record RepresentativeResponse(UUID id, String name, String email, String phone, UserStatus status) {
        public static RepresentativeResponse from(AppUser u) {
            return new RepresentativeResponse(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getStatus());
        }
    }
}
