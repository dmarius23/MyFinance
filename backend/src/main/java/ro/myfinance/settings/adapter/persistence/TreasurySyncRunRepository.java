package ro.myfinance.settings.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.TreasurySyncRun;

/** Global ANAF sync-run history — no tenant scoping (SUPER_ADMIN-only reference operation). */
public interface TreasurySyncRunRepository extends JpaRepository<TreasurySyncRun, UUID> {

    /** Newest runs first for the admin screen. */
    List<TreasurySyncRun> findAllByOrderByStartedAtDesc();
}
