package ro.myfinance.settings.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.SyncChange;
import ro.myfinance.settings.domain.TreasurySyncItem;

/** Global ANAF sync-item diff rows — no tenant scoping (SUPER_ADMIN-only reference operation). */
public interface TreasurySyncItemRepository extends JpaRepository<TreasurySyncItem, UUID> {

    List<TreasurySyncItem> findByRunIdOrderByCountyAscResidenceAsc(UUID runId);

    /** The reviewable subset (e.g. ADDED + CHANGED), or the applied subset. */
    List<TreasurySyncItem> findByRunIdAndChangeInOrderByCountyAscResidenceAsc(UUID runId,
                                                                             Collection<SyncChange> changes);
}
