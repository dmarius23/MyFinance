package ro.myfinance.common.email;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-tenant email provider config (RLS-scoped). */
public interface TenantEmailProviderRepository extends JpaRepository<TenantEmailProvider, UUID> {
}
