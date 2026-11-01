package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.PlatformDeclarationDeadline;

/** Global salary-tax deadline reference data — no tenant scoping (these rows are national/public). */
public interface PlatformDeclarationDeadlineRepository extends JpaRepository<PlatformDeclarationDeadline, UUID> {

    /** Effective-dated lookup: the rule in force for {@code period} = greatest valid_from &lt;= period. */
    Optional<PlatformDeclarationDeadline> findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate period);

    /** Full history for the admin screen, newest effective date first. */
    List<PlatformDeclarationDeadline> findAllByOrderByValidFromDesc();

    /** Guards valid_from uniqueness at the app layer for a friendly conflict message. */
    boolean existsByValidFrom(LocalDate validFrom);
}
