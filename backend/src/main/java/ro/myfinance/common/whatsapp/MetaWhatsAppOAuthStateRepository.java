package ro.myfinance.common.whatsapp;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS-scoped persistence for short-lived Meta OAuth state. */
public interface MetaWhatsAppOAuthStateRepository extends JpaRepository<MetaWhatsAppOAuthState, UUID> {

    Optional<MetaWhatsAppOAuthState> findByStateHash(String stateHash);

    /**
     * Atomically marks the state consumed if it is still unused and unexpired; returns 1 for the caller
     * that won, 0 for any concurrent or replayed completion. A competing transaction blocks on the row lock
     * and then re-checks {@code consumed_at}, so exactly one completion can proceed to Meta.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MetaWhatsAppOAuthState s set s.consumedAt = :now
             where s.id = :id and s.consumedAt is null and s.expiresAt > :now
            """)
    int consumeIfUsable(@Param("id") UUID id, @Param("now") Instant now);
}
