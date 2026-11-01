package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.persistence.PlatformDeclarationDeadlineRepository;
import ro.myfinance.settings.adapter.persistence.PlatformTaxRateRepository;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.application.PlatformReferenceAdminService;
import ro.myfinance.settings.domain.PlatformDeclarationDeadline;

/**
 * Write-side validation for the salary-tax deadline. The day is constrained to 1..28 in the database
 * too, but rejecting it here gives the admin a readable message instead of a constraint violation.
 */
class PlatformDeclarationDeadlineAdminTest {

    private final PlatformDeclarationDeadlineRepository repo = mock(PlatformDeclarationDeadlineRepository.class);

    /** The admin service takes two pre-existing repositories; the deadline one is appended last. */
    private PlatformReferenceAdminService service() {
        return new PlatformReferenceAdminService(
                mock(PlatformTaxRateRepository.class),
                mock(PlatformTreasuryAccountRepository.class),
                repo);
    }

    @Test
    void acceptsADayWithinRange() {
        when(repo.existsByValidFrom(LocalDate.of(2027, 1, 1))).thenReturn(false);
        when(repo.save(any(PlatformDeclarationDeadline.class))).thenAnswer(i -> i.getArgument(0));
        var saved = service().addDeclarationDeadline((short) 20, LocalDate.of(2027, 1, 1));
        assertThat(saved.getDayOfMonth()).isEqualTo((short) 20);
        assertThat(saved.getValidFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void rejectsADayBelowOne() {
        assertThatThrownBy(() -> service().addDeclarationDeadline((short) 0, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 28");
    }

    @Test
    void rejectsADayAboveTwentyEight() {
        assertThatThrownBy(() -> service().addDeclarationDeadline((short) 31, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 28");
    }

    @Test
    void rejectsAMissingEffectiveDate() {
        assertThatThrownBy(() -> service().addDeclarationDeadline((short) 25, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Effective date");
    }

    @Test
    void rejectsADuplicateEffectiveDate() {
        when(repo.existsByValidFrom(LocalDate.of(2027, 1, 1))).thenReturn(true);
        assertThatThrownBy(() -> service().addDeclarationDeadline((short) 20, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(ro.myfinance.common.web.ConflictException.class);
    }

    @Test
    void updatingAMissingRowIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateDeclarationDeadline(id, (short) 20))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
    }
}
