package ro.myfinance.common.whatsapp;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-tenant WhatsApp provider config (RLS-scoped). */
public interface TenantWhatsAppProviderRepository extends JpaRepository<TenantWhatsAppProvider, UUID> {
}
