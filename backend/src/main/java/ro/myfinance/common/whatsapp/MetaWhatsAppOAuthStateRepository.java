package ro.myfinance.common.whatsapp;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS-scoped persistence for short-lived Meta OAuth state. */
public interface MetaWhatsAppOAuthStateRepository extends JpaRepository<MetaWhatsAppOAuthState, UUID> {

    Optional<MetaWhatsAppOAuthState> findByStateHash(String stateHash);
}
